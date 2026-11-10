package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.Built;
import com.invigilo.allocation.ChartEditService.DutySnapshot;
import com.invigilo.allocation.SwapRequest.Status;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.ChartChecker;
import com.invigilo.allocation.engine.ChartChecker.Placement;
import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.common.persistence.Versions;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamSession;
import com.invigilo.exam.SessionHall;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyService;
import com.invigilo.identity.AppUser;
import com.invigilo.identity.AppUserRepository;
import com.invigilo.policy.PolicyService;
import com.invigilo.policy.PolicySettings.SwapRules;
import com.invigilo.tenant.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Duty swaps (M13). A faculty member offers one of their duties in a PUBLISHED chart to a colleague,
 * as a handover or in exchange for one of the colleague's duties. Depending on the college's swap
 * rules the colleague accepts and a HOD approves; then the swap is applied in one transaction that
 * locks the chart and checks both moves again with {@link ChartChecker}, i.e. with the hard rules of
 * generation. A swap that no longer fits (the chart was replaced, someone went on leave, a duty
 * started) is marked INVALIDATED instead of applied. Every step is audited; applied swaps also
 * appear in the chart's history.
 */
@Service
public class SwapService {

    public record PersonRef(UUID id, String code, String name, String departmentCode) {}

    public record DutyRef(UUID assignmentId, LocalDate date, String sessionLabel, LocalTime startTime, LocalTime endTime,
                          String hallCode, String hallName, Role role) {}

    /** {@code targetDuty} is null for a handover. {@code version} goes back with every action. */
    public record SwapView(UUID id, UUID periodId, String periodName, Status status, PersonRef requester, DutyRef requesterDuty,
                           PersonRef target, DutyRef targetDuty, String reason, Instant createdAt, Instant recipientRespondedAt,
                           String recipientNote, String reviewedByName, Instant reviewedAt, String reviewNote, String invalidReason,
                           Instant appliedAt, long version) {}

    public record SwapInput(UUID assignmentId, UUID targetFacultyId, UUID targetAssignmentId, String reason) {}

    public record Issue(String code, String message) {}

    /** Whether the swap would break a hard rule right now; warnings are soft costs worth knowing. */
    public record SwapCheck(boolean allowed, List<Issue> blocking, List<String> warnings) {}

    /**
     * A colleague to swap with and their upcoming duties that could be offered back. With
     * {@code canTakeHandover} false they could not simply take the duty (e.g. busy in that session
     * or at their daily limit), but an exchange may still fit: the check decides.
     */
    public record Colleague(PersonRef faculty, int duties, boolean canTakeHandover, List<String> warnings,
                            List<DutyRef> exchangeable) {}

    public record SwapOptions(DutyRef duty, boolean swapsEnabled, List<Colleague> colleagues) {}

    /** Requests the caller made, and requests made to them. */
    public record MySwaps(List<SwapView> sent, List<SwapView> received) {}

    static final String ENTITY = "swap_request";
    private static final Set<Status> ALL = EnumSet.allOf(Status.class);

    /** The published chart a swap works on, with everything needed to check it. */
    private record Chart(DutyChart chart, ExamPeriod period, Built built, List<DutyAssignment> duties) {}

    private final SwapRequestRepository swaps;
    private final DutyAssignmentRepository assignments;
    private final DutyChartRepository charts;
    private final AllocationService allocation;
    private final ExamPeriodService periods;
    private final FacultyService facultyService;
    private final PolicyService policies;
    private final OwnFaculty ownFaculty;
    private final CollegeTime collegeTime;
    private final AppUserRepository users;
    private final AuditService audit;
    private final Clock clock;

    SwapService(SwapRequestRepository swaps, DutyAssignmentRepository assignments, DutyChartRepository charts,
                AllocationService allocation, ExamPeriodService periods, FacultyService facultyService, PolicyService policies,
                OwnFaculty ownFaculty, CollegeTime collegeTime, AppUserRepository users, AuditService audit, Clock clock) {
        this.swaps = swaps;
        this.assignments = assignments;
        this.charts = charts;
        this.allocation = allocation;
        this.periods = periods;
        this.facultyService = facultyService;
        this.policies = policies;
        this.ownFaculty = ownFaculty;
        this.collegeTime = collegeTime;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- faculty: asking ----

    /**
     * Colleagues to swap one of the caller's duties with: first those who could legally take it as a
     * handover (cheapest first, as generation ranks them), then everyone else with upcoming duties
     * in the chart, who can only exchange.
     */
    @Transactional(readOnly = true)
    public SwapOptions options(CurrentUser user, UUID assignmentId) {
        UUID me = ownFaculty.requireId(user);
        DutyAssignment duty = requireOwnUpcomingDuty(me, assignmentId);
        Chart c = load(duty.getChartId());
        ChartChecker checker = new ChartChecker(c.built().problem(), AllocationService.placements(c.duties()));
        if (!checker.isCandidate(me)) return new SwapOptions(dutyRef(duty), rules().swapsEnabled(), List.of());

        Map<UUID, List<DutyRef>> upcoming = c.duties().stream()
                .filter(d -> d.getStatus() == DutyAssignment.Status.ASSIGNED && !started(d)
                        && !d.getSessionHall().getId().equals(duty.getSessionHall().getId()))
                .sorted(Comparator.comparing((DutyAssignment d) -> d.getSessionHall().getSession().getSessionDate())
                        .thenComparing(d -> d.getSessionHall().getSession().getStartTime()))
                .collect(Collectors.groupingBy(d -> d.getFaculty().getId(), Collectors.mapping(SwapService::dutyRef, Collectors.toList())));
        List<Colleague> colleagues = new ArrayList<>();
        Set<UUID> canTake = new HashSet<>();
        for (ChartChecker.Alternative a : checker.alternatives(duty.getSessionHall().getId(), duty.getRole(),
                AllocationService.placement(duty), Integer.MAX_VALUE)) {
            Faculty f = c.built().faculty().get(a.facultyId());
            canTake.add(f.getId());
            colleagues.add(new Colleague(person(f), a.duties(), true, a.warnings(), upcoming.getOrDefault(f.getId(), List.of())));
        }
        c.built().faculty().values().stream()
                .filter(f -> !f.getId().equals(me) && !canTake.contains(f.getId()) && upcoming.containsKey(f.getId()))
                .sorted(Comparator.comparing(Faculty::getFullName).thenComparing(Faculty::getEmployeeCode))
                .forEach(f -> colleagues.add(new Colleague(person(f), checker.duties(f.getId()), false, List.of(), upcoming.get(f.getId()))));
        return new SwapOptions(dutyRef(duty), rules().swapsEnabled(), colleagues);
    }

    /** Checks a swap without asking for it. */
    @Transactional(readOnly = true)
    public SwapCheck validate(CurrentUser user, SwapInput input) {
        UUID me = ownFaculty.requireId(user);
        DutyAssignment duty = requireOwnUpcomingDuty(me, input.assignmentId());
        Chart c = load(duty.getChartId());
        Faculty target = requireTarget(c, me, input.targetFacultyId());
        DutyAssignment theirs = requireTheirDuty(c, duty, target, input.targetAssignmentId());
        return evaluate(c, duty, target, theirs);
    }

    @Transactional
    public SwapView request(CurrentUser user, SwapInput input) {
        SwapRules rules = rules();
        if (!rules.swapsEnabled()) {
            throw ApiException.unprocessable("SWAPS_DISABLED", "Your college does not allow duty swaps. Ask the examination cell.");
        }
        UUID me = ownFaculty.requireId(user);
        DutyAssignment duty = requireOwnUpcomingDuty(me, input.assignmentId());
        Chart c = load(duty.getChartId());
        Faculty target = requireTarget(c, me, input.targetFacultyId());
        DutyAssignment theirs = requireTheirDuty(c, duty, target, input.targetAssignmentId());
        if (swaps.existsByRequesterAssignment_IdAndStatusIn(duty.getId(), SwapRequest.OPEN)) {
            throw ApiException.conflict("SWAP_ALREADY_REQUESTED", "You have already asked for a swap of this duty. Cancel that request first.");
        }
        SwapCheck check = evaluate(c, duty, target, theirs);
        if (!check.allowed()) {
            throw ApiException.unprocessable("HARD_RULE_VIOLATED", "This swap breaks a strict rule: "
                    + check.blocking().stream().map(Issue::message).collect(Collectors.joining(" ")));
        }
        Status first = rules.recipientMustAccept() ? Status.PENDING_RECIPIENT
                : rules.hodApprovalRequired() ? Status.PENDING_HOD : Status.APPROVED;
        SwapRequest swap = swaps.saveAndFlush(new SwapRequest(duty, target, theirs, note(input.reason()), user.userId(),
                first == Status.APPROVED ? Status.PENDING_RECIPIENT : first));
        audit.record("SWAP_REQUESTED", ENTITY, swap.getId(), null, view(swap), swap.getReason());
        if (first == Status.APPROVED) apply(swap, user);
        return view(swap);
    }

    // ---- faculty: answering and cancelling ----

    @Transactional(readOnly = true)
    public MySwaps mine(CurrentUser user) {
        UUID me = ownFaculty.requireId(user);
        List<SwapRequest> all = swaps.findInvolving(me);
        Map<UUID, String> names = reviewerNames(all);
        return new MySwaps(
                all.stream().filter(s -> s.getRequester().getId().equals(me)).map(s -> view(s, names)).toList(),
                all.stream().filter(s -> s.getTarget().getId().equals(me)).map(s -> view(s, names)).toList());
    }

    /** The colleague agrees; the swap goes to the HOD or is applied. */
    @Transactional
    public SwapView accept(CurrentUser user, UUID id, Long version, String noteText) {
        SwapRequest swap = requireAsTarget(user, id);
        requireStatus(swap, Status.PENDING_RECIPIENT, version);
        SwapView before = view(swap);
        boolean needsHod = rules().hodApprovalRequired();
        swap.recipientResponded(needsHod ? Status.PENDING_HOD : Status.PENDING_RECIPIENT, clock.instant(), note(noteText));
        swaps.flush();
        audit.record("SWAP_ACCEPTED", ENTITY, swap.getId(), before, view(swap), swap.getRecipientNote());
        if (!needsHod) apply(swap, user);
        return view(swap);
    }

    @Transactional
    public SwapView decline(CurrentUser user, UUID id, Long version, String noteText) {
        SwapRequest swap = requireAsTarget(user, id);
        requireStatus(swap, Status.PENDING_RECIPIENT, version);
        SwapView before = view(swap);
        swap.recipientResponded(Status.REJECTED, clock.instant(), note(noteText));
        swaps.flush();
        audit.record("SWAP_DECLINED", ENTITY, swap.getId(), before, view(swap), swap.getRecipientNote());
        return view(swap);
    }

    @Transactional
    public SwapView cancel(CurrentUser user, UUID id, Long version) {
        UUID me = ownFaculty.requireId(user);
        SwapRequest swap = find(id);
        if (!swap.getRequester().getId().equals(me)) throw notFound();
        if (!swap.isOpen()) throw notOpen(swap);
        Versions.requireUnchanged(swap, version);
        SwapView before = view(swap);
        swap.cancel();
        swaps.flush();
        audit.record("SWAP_CANCELLED", ENTITY, swap.getId(), before, view(swap), null);
        return view(swap);
    }

    // ---- HOD and exam cell ----

    /** Exam cell: every request. HOD: requests from faculty of their department. */
    @Transactional(readOnly = true)
    public List<SwapView> list(CurrentUser user, Status status) {
        UUID departmentId = user.hasRole(com.invigilo.identity.Role.EXAM_CELL_ADMIN) ? null
                : ownFaculty.requireHodDepartment(user).getId();
        List<SwapRequest> found = swaps.search(status == null ? ALL : EnumSet.of(status), departmentId);
        Map<UUID, String> names = reviewerNames(found);
        return found.stream().map(s -> view(s, names)).toList();
    }

    @Transactional
    public SwapView approve(CurrentUser user, UUID id, Long version, String noteText) {
        SwapRequest swap = requireReviewable(user, id, version);
        SwapView before = view(swap);
        swap.reviewed(Status.PENDING_HOD, user.userId(), clock.instant(), note(noteText));
        swaps.flush();
        audit.record("SWAP_APPROVED", ENTITY, swap.getId(), before, view(swap), swap.getReviewNote());
        apply(swap, user);
        return view(swap);
    }

    @Transactional
    public SwapView reject(CurrentUser user, UUID id, Long version, String noteText) {
        SwapRequest swap = requireReviewable(user, id, version);
        SwapView before = view(swap);
        swap.reviewed(Status.REJECTED, user.userId(), clock.instant(), note(noteText));
        swaps.flush();
        audit.record("SWAP_REJECTED", ENTITY, swap.getId(), before, view(swap), swap.getReviewNote());
        return view(swap);
    }

    // ---- applying ----

    /**
     * Applies an agreed swap, or marks it INVALIDATED when it no longer fits. Locks the chart so
     * concurrent swaps are checked one after the other against the chart as it really is.
     */
    private void apply(SwapRequest swap, CurrentUser user) {
        DutyChart chart = charts.lockById(swap.getChartId()).orElseThrow();
        String why = applyProblem(swap, chart);
        if (why == null) {
            Chart c = load(chart.getId());
            DutyAssignment mine = byId(c, swap.getRequesterAssignment().getId());
            DutyAssignment theirs = swap.isHandover() ? null : byId(c, swap.getTargetAssignment().getId());
            SwapCheck check = evaluate(c, mine, swap.getTarget(), theirs);
            if (!check.allowed()) {
                why = check.blocking().stream().map(Issue::message).collect(Collectors.joining(" "));
            } else {
                List<DutySnapshot> before = new ArrayList<>(List.of(DutySnapshot.of(mine)));
                if (theirs != null) before.add(DutySnapshot.of(theirs));
                mine.swapTo(swap.getTarget());
                if (theirs != null) theirs.swapTo(swap.getRequester());
                assignments.flush();
                List<DutySnapshot> after = new ArrayList<>(List.of(DutySnapshot.of(mine)));
                if (theirs != null) after.add(DutySnapshot.of(theirs));
                swap.applied(clock.instant());
                swaps.flush();
                audit.record(theirs == null ? "DUTY_HANDED_OVER" : "DUTY_SWAPPED", ChartEditService.ENTITY, chart.getId(),
                        before, after, swap.getReason());
                audit.record("SWAP_APPLIED", ENTITY, swap.getId(), null, view(swap), null);
                return;
            }
        }
        swap.invalidate(why);
        swaps.flush();
        audit.record("SWAP_INVALIDATED", ENTITY, swap.getId(), null, view(swap), why);
    }

    /** Why the swap can no longer be applied, before checking the rules; null when it still can. */
    private String applyProblem(SwapRequest swap, DutyChart chart) {
        if (chart.getStatus() != DutyChart.Status.PUBLISHED) {
            return "A newer version of the duty chart has been published, so this swap no longer applies.";
        }
        if (!periods.findPeriod(chart.getPeriodId()).allowsChartChanges()) {
            return "The examination period is closed.";
        }
        DutyAssignment mine = swap.getRequesterAssignment();
        if (!mine.getFaculty().getId().equals(swap.getRequester().getId()) || mine.getStatus() != DutyAssignment.Status.ASSIGNED) {
            return swap.getRequester().getFullName() + " no longer has the offered duty.";
        }
        if (started(mine)) return "The offered duty has already started.";
        DutyAssignment theirs = swap.getTargetAssignment();
        if (theirs != null) {
            if (!theirs.getFaculty().getId().equals(swap.getTarget().getId()) || theirs.getStatus() != DutyAssignment.Status.ASSIGNED) {
                return swap.getTarget().getFullName() + " no longer has the duty offered in exchange.";
            }
            if (started(theirs)) return "The duty offered in exchange has already started.";
        }
        return null;
    }

    /**
     * The chart as it would be after the swap, judged by the engine: only the moved duties count,
     * so problems elsewhere in the chart do not block an unrelated swap.
     */
    private SwapCheck evaluate(Chart c, DutyAssignment mine, Faculty target, DutyAssignment theirs) {
        Faculty requester = mine.getFaculty();
        List<Issue> blocking = new ArrayList<>();
        if (!c.built().faculty().containsKey(target.getId())) {
            blocking.add(new Issue("FACULTY_INACTIVE", target.getFullName() + " is inactive and cannot take duties."));
        }
        if (theirs != null && !c.built().faculty().containsKey(requester.getId())) {
            blocking.add(new Issue("FACULTY_INACTIVE", requester.getFullName() + " is inactive and cannot take duties."));
        }
        if (!blocking.isEmpty()) return new SwapCheck(false, List.copyOf(blocking), List.of());

        Placement toTarget = new Placement(mine.getSessionHall().getId(), target.getId(), mine.getRole());
        Placement toRequester = theirs == null ? null : new Placement(theirs.getSessionHall().getId(), requester.getId(), theirs.getRole());
        List<Placement> after = new ArrayList<>();
        for (DutyAssignment d : c.duties()) {
            if (d.getStatus() == DutyAssignment.Status.CANCELLED) continue;
            if (d.getId().equals(mine.getId())) after.add(toTarget);
            else if (theirs != null && d.getId().equals(theirs.getId())) after.add(toRequester);
            else after.add(AllocationService.placement(d));
        }
        Set<Placement> moved = new HashSet<>();
        moved.add(toTarget);
        if (toRequester != null) moved.add(toRequester);
        for (ChartChecker.Violation v : new ChartChecker(c.built().problem(), after).violations()) {
            if (!moved.contains(v.placement())) continue;
            Faculty who = v.placement().facultyId().equals(target.getId()) ? target : requester;
            v.reasons().forEach(r -> blocking.add(new Issue(r.name(), who.getFullName() + " " + r.singular() + ".")));
        }

        // Soft costs of each side taking the other's duty, as the chart editor shows them.
        List<String> warnings = new ArrayList<>();
        ChartChecker now = new ChartChecker(c.built().problem(), AllocationService.placements(c.duties()));
        if (now.isCandidate(requester.getId())) {
            now.check(mine.getSessionHall().getId(), mine.getRole(), target.getId(), AllocationService.placement(mine)).warnings()
                    .forEach(w -> warnings.add(target.getFullName() + " " + w));
        }
        if (theirs != null && now.isCandidate(target.getId())) {
            now.check(theirs.getSessionHall().getId(), theirs.getRole(), requester.getId(), AllocationService.placement(theirs))
                    .warnings().forEach(w -> warnings.add(requester.getFullName() + " " + w));
        }
        return new SwapCheck(blocking.isEmpty(), List.copyOf(blocking), List.copyOf(warnings));
    }

    // ---- lookups and rules ----

    private Chart load(UUID chartId) {
        DutyChart chart = charts.findById(chartId).orElseThrow(SwapService::notFound);
        ExamPeriod period = periods.findPeriod(chart.getPeriodId());
        List<DutyAssignment> duties = assignments.findForChart(chart.getId()).stream()
                .filter(d -> d.getStatus() != DutyAssignment.Status.CANCELLED)
                .toList();
        return new Chart(chart, period, allocation.buildForChart(period, chart), duties);
    }

    private static DutyAssignment byId(Chart c, UUID assignmentId) {
        return c.duties().stream().filter(d -> d.getId().equals(assignmentId)).findFirst().orElseThrow();
    }

    /** One of the caller's duties in a published chart that has not started yet. */
    private DutyAssignment requireOwnUpcomingDuty(UUID me, UUID assignmentId) {
        if (assignmentId == null) throw ApiException.unprocessable("ASSIGNMENT_REQUIRED", "Choose the duty you want to swap");
        DutyAssignment duty = assignments.findById(assignmentId)
                .filter(d -> d.getFaculty().getId().equals(me))
                .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND", "That duty is not one of yours"));
        requireSwappable(duty, "Your duty");
        return duty;
    }

    private void requireSwappable(DutyAssignment duty, String whose) {
        DutyChart chart = charts.findById(duty.getChartId()).orElseThrow();
        if (chart.getStatus() != DutyChart.Status.PUBLISHED) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", whose + " is not in the published duty chart");
        }
        if (!periods.findPeriod(chart.getPeriodId()).allowsChartChanges()) {
            throw ApiException.conflict("PERIOD_LOCKED", "This examination period is closed; its duties can no longer change");
        }
        if (duty.getStatus() != DutyAssignment.Status.ASSIGNED) {
            throw ApiException.conflict("DUTY_NOT_ASSIGNED", whose + " is " + duty.getStatus().name().toLowerCase() + " and cannot be swapped");
        }
        if (started(duty)) throw ApiException.conflict("DUTY_STARTED", whose + " has already started and can no longer be swapped");
    }

    private Faculty requireTarget(Chart c, UUID me, UUID targetFacultyId) {
        if (targetFacultyId == null) throw ApiException.unprocessable("FACULTY_REQUIRED", "Choose the colleague to swap with");
        if (targetFacultyId.equals(me)) throw ApiException.unprocessable("SAME_FACULTY", "Choose a colleague, not yourself");
        Faculty f = c.built().faculty().get(targetFacultyId);
        if (f != null) return f;
        Faculty inactive = facultyService.find(targetFacultyId); // 404 for unknown or other-college ids
        throw ApiException.unprocessable("FACULTY_INACTIVE", inactive.getFullName() + " is inactive and cannot take duties");
    }

    private DutyAssignment requireTheirDuty(Chart c, DutyAssignment mine, Faculty target, UUID targetAssignmentId) {
        if (targetAssignmentId == null) return null;
        DutyAssignment theirs = c.duties().stream()
                .filter(d -> d.getId().equals(targetAssignmentId) && d.getFaculty().getId().equals(target.getId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND",
                        "That duty is not one of " + target.getFullName() + "'s in the same duty chart"));
        requireSwappable(theirs, target.getFullName() + "'s duty");
        if (theirs.getSessionHall().getId().equals(mine.getSessionHall().getId())) {
            throw ApiException.unprocessable("SAME_HALL", "You are both on duty in the same hall and session; there is nothing to swap");
        }
        return theirs;
    }

    private SwapRequest requireAsTarget(CurrentUser user, UUID id) {
        UUID me = ownFaculty.requireId(user);
        SwapRequest swap = find(id);
        if (!swap.getTarget().getId().equals(me)) throw notFound();
        return swap;
    }

    /** A request waiting for a HOD that this caller may decide: exam cell, or HOD of the requester's department. */
    private SwapRequest requireReviewable(CurrentUser user, UUID id, Long version) {
        SwapRequest swap = find(id);
        boolean admin = user.hasRole(com.invigilo.identity.Role.EXAM_CELL_ADMIN);
        if (!admin) {
            UUID department = ownFaculty.requireHodDepartment(user).getId();
            if (!swap.getRequester().getDepartment().getId().equals(department)) throw notFound();
        }
        UUID me = ownFaculty.id(user).orElse(null);
        if (me != null && (me.equals(swap.getRequester().getId()) || me.equals(swap.getTarget().getId()))) {
            throw ApiException.forbidden("OWN_SWAP", "You cannot approve or reject a swap you are part of");
        }
        requireStatus(swap, Status.PENDING_HOD, version);
        return swap;
    }

    private static void requireStatus(SwapRequest swap, Status expected, Long version) {
        if (swap.getStatus() != expected) throw notOpen(swap);
        Versions.requireUnchanged(swap, version);
    }

    private boolean started(DutyAssignment d) {
        ExamSession s = d.getSessionHall().getSession();
        return !s.getSessionDate().atTime(s.getStartTime()).isAfter(collegeTime.now());
    }

    private SwapRules rules() {
        return policies.current().settings().swaps();
    }

    private SwapRequest find(UUID id) {
        return swaps.findById(id).orElseThrow(SwapService::notFound);
    }

    private static ApiException notFound() {
        return ApiException.notFound("SWAP_NOT_FOUND", "Swap request not found");
    }

    private static ApiException notOpen(SwapRequest swap) {
        return ApiException.conflict("SWAP_NOT_PENDING", "This swap request is already " + describe(swap.getStatus()));
    }

    private static String describe(Status s) {
        return switch (s) {
            case PENDING_RECIPIENT -> "waiting for the colleague";
            case PENDING_HOD -> "waiting for the head of department";
            case APPROVED -> "done";
            case REJECTED -> "rejected";
            case CANCELLED -> "cancelled";
            case INVALIDATED -> "no longer possible";
        };
    }

    private static String note(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.strip().length() > 500) throw ApiException.unprocessable("REASON_TOO_LONG", "Keep the note under 500 characters");
        return s.strip();
    }

    // ---- views ----

    private SwapView view(SwapRequest s) {
        return view(s, reviewerNames(List.of(s)));
    }

    private Map<UUID, String> reviewerNames(List<SwapRequest> list) {
        Set<UUID> ids = list.stream().map(SwapRequest::getReviewedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
    }

    private static SwapView view(SwapRequest s, Map<UUID, String> names) {
        ExamPeriod period = s.getRequesterAssignment().getSessionHall().getSession().getPeriod();
        return new SwapView(s.getId(), period.getId(), period.getName(), s.getStatus(), person(s.getRequester()),
                dutyRef(s.getRequesterAssignment()), person(s.getTarget()),
                s.getTargetAssignment() == null ? null : dutyRef(s.getTargetAssignment()), s.getReason(), s.getCreatedAt(),
                s.getRecipientRespondedAt(), s.getRecipientNote(), s.getReviewedBy() == null ? null : names.get(s.getReviewedBy()),
                s.getReviewedAt(), s.getReviewNote(), s.getInvalidReason(), s.getAppliedAt(), s.getVersion());
    }

    private static PersonRef person(Faculty f) {
        return new PersonRef(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getCode());
    }

    private static DutyRef dutyRef(DutyAssignment d) {
        SessionHall sh = d.getSessionHall();
        ExamSession s = sh.getSession();
        return new DutyRef(d.getId(), s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime(), sh.getHall().getCode(),
                sh.getHall().getName(), d.getRole());
    }

    /** Faculty named in swap requests (either side) cannot be deleted. */
    @Component
    static class FacultySwaps implements UsageCheck<Faculty> {
        private final SwapRequestRepository swaps;

        FacultySwaps(SwapRequestRepository swaps) {
            this.swaps = swaps;
        }

        @Override
        public String usedBy() {
            return "swap request(s)";
        }

        @Override
        public long countUsages(UUID facultyId) {
            return swaps.countByRequester_IdOrTarget_Id(facultyId, facultyId);
        }
    }
}
