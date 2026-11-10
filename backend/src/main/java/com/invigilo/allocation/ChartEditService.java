package com.invigilo.allocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.invigilo.allocation.AllocationService.Built;
import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.ChartChecker;
import com.invigilo.allocation.engine.ChartChecker.Placement;
import com.invigilo.allocation.engine.Reason;
import com.invigilo.audit.AuditEntry;
import com.invigilo.audit.AuditRepository;
import com.invigilo.audit.AuditService;
import com.invigilo.availability.AvailabilityService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Versions;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.SessionHall;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyService;
import com.invigilo.identity.AppUser;
import com.invigilo.identity.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual editing of a period's DRAFT duty chart (M9): check a change, add a duty to an open
 * position, give a duty to someone else, or remove it. Every change is judged by
 * {@link ChartChecker}, i.e. by the same hard rules and soft costs as generation, and is refused
 * if it breaks a hard rule. Edits lock the draft for their transaction and are audited; the audit
 * entries are the chart's edit history.
 */
@Service
public class ChartEditService {

    static final int ALTERNATIVES = 8;
    static final String ENTITY = "duty_chart";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    /** A rule problem with a change, e.g. {@code BUSY_IN_SESSION}, with a sentence for people. */
    public record Issue(String code, String message) {}

    /** A faculty member who could take the position, cheapest first. */
    public record Suggestion(UUID facultyId, String code, String name, String departmentCode, int duties, List<String> warnings) {}

    /** Result of checking a change without making it. {@code allowed} means no blocking issue. */
    public record Validation(boolean allowed, List<Issue> blocking, List<String> warnings, List<Suggestion> alternatives) {}

    public record HistoryEntry(UUID id, Instant occurredAt, String action, String actorName, String summary, String reason) {}

    /** What the audit log keeps about one duty. */
    record DutySnapshot(UUID assignmentId, LocalDate date, String session, String hallCode, Role role, UUID facultyId,
                        String facultyCode, String facultyName) {
        static DutySnapshot of(DutyAssignment d) {
            SessionHall sh = d.getSessionHall();
            Faculty f = d.getFaculty();
            return new DutySnapshot(d.getId(), sh.getSession().getSessionDate(), sh.getSession().getLabel(), sh.getHall().getCode(),
                    d.getRole(), f.getId(), f.getEmployeeCode(), f.getFullName());
        }
    }

    /** The draft being edited and everything needed to judge changes to it. */
    private record Editing(ExamPeriod period, DutyChart chart, Built built, List<DutyAssignment> duties, ChartChecker checker) {}

    private final AllocationService allocation;
    private final ExamPeriodService periods;
    private final FacultyService facultyService;
    private final AvailabilityService availability;
    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final UnfilledPositionRepository unfilled;
    private final AuditService audit;
    private final AuditRepository auditLog;
    private final AppUserRepository users;

    public ChartEditService(AllocationService allocation, ExamPeriodService periods, FacultyService facultyService,
                            AvailabilityService availability, DutyChartRepository charts, DutyAssignmentRepository assignments,
                            UnfilledPositionRepository unfilled, AuditService audit, AuditRepository auditLog,
                            AppUserRepository users) {
        this.allocation = allocation;
        this.periods = periods;
        this.facultyService = facultyService;
        this.availability = availability;
        this.charts = charts;
        this.assignments = assignments;
        this.unfilled = unfilled;
        this.audit = audit;
        this.auditLog = auditLog;
        this.users = users;
    }

    // ---- checking ----

    /**
     * Checks a change without making it. With {@code replacesAssignmentId} the change gives that
     * duty to {@code facultyId}; otherwise it fills an open position of the hall and role. Without
     * {@code facultyId}, only the alternatives are returned (to pick someone).
     */
    @Transactional(readOnly = true)
    public Validation validate(UUID periodId, UUID sessionHallId, Role role, UUID facultyId, UUID replacesAssignmentId) {
        Editing e = load(periodId, false);
        DutyAssignment replacing = replacesAssignmentId == null ? null : requireDuty(e, replacesAssignmentId);
        SessionHall hall = replacing != null ? replacing.getSessionHall() : requireHall(e, sessionHallId);
        Role seatRole = replacing != null ? replacing.getRole() : role;
        if (seatRole == null) throw ApiException.unprocessable("ROLE_REQUIRED", "Choose chief or invigilator");
        Placement seat = replacing == null ? null : loaded(e, replacing);

        List<Issue> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (replacing == null && openCount(e, hall, seatRole) <= 0) {
            blocking.add(new Issue("POSITION_NOT_OPEN", hall.getHall().getCode() + " already has every "
                    + roleName(seatRole) + " it needs. Replace someone instead."));
        }
        Set<UUID> pending = pendingFor(e, hall);
        if (facultyId != null) {
            Faculty f = requireActiveFaculty(e, facultyId);
            if (replacing != null && replacing.getFaculty().getId().equals(facultyId)) {
                blocking.add(new Issue("SAME_FACULTY", f.getFullName() + " already has this duty."));
            } else {
                ChartChecker.Check check = e.checker().check(hall.getId(), seatRole, facultyId, seat);
                check.blocking().forEach(r -> blocking.add(issue(f, r)));
                warnings.addAll(check.warnings());
                if (pending.contains(facultyId)) warnings.add("has a leave request for this session waiting for a decision");
            }
        }
        List<Suggestion> alternatives = e.checker().alternatives(hall.getId(), seatRole, seat, ALTERNATIVES).stream()
                .map(a -> suggestion(e.built().faculty().get(a.facultyId()), a, pending))
                .toList();
        return new Validation(blocking.isEmpty(), List.copyOf(blocking), List.copyOf(warnings), alternatives);
    }

    // ---- changes ----

    /** Puts {@code facultyId} in an open position of the hall. */
    @Transactional
    public ChartView add(UUID periodId, UUID sessionHallId, Role role, UUID facultyId, String reason) {
        Editing e = load(periodId, true);
        SessionHall hall = requireHall(e, sessionHallId);
        UnfilledPosition gap = unfilled.findByChartIdAndSessionHall_IdAndRole(e.chart().getId(), hall.getId(), role)
                .filter(g -> g.getMissing() > 0)
                .orElseThrow(() -> ApiException.conflict("POSITION_NOT_OPEN", hall.getHall().getCode() + " already has every "
                        + roleName(role) + " it needs. Replace someone instead."));
        Faculty f = requireActiveFaculty(e, facultyId);
        requireAllowed(f, e.checker().check(hall.getId(), role, facultyId, null));

        DutyAssignment duty = assignments.saveAndFlush(DutyAssignment.manual(e.chart().getId(), hall, f, role));
        List<Placement> after = new ArrayList<>(AllocationService.placements(e.duties()));
        after.add(AllocationService.placement(duty));
        int missing = gap.getMissing() - 1;
        if (missing == 0) {
            unfilled.delete(gap);
        } else {
            gap.update(missing, explainOpen(e, after, hall, role, missing));
        }
        audit.record("DUTY_ASSIGNED", ENTITY, e.chart().getId(), null, DutySnapshot.of(duty), blankToNull(reason));
        return refreshed(e);
    }

    /** Gives a duty to someone else. {@code version} is the duty's version when it was loaded. */
    @Transactional
    public ChartView replace(UUID periodId, UUID assignmentId, UUID facultyId, Long version, String reason) {
        Editing e = load(periodId, true);
        DutyAssignment duty = requireDuty(e, assignmentId);
        Versions.requireUnchanged(duty, version);
        Faculty f = requireActiveFaculty(e, facultyId);
        if (duty.getFaculty().getId().equals(facultyId)) {
            throw ApiException.unprocessable("SAME_FACULTY", f.getFullName() + " already has this duty");
        }
        requireAllowed(f, e.checker().check(duty.getSessionHall().getId(), duty.getRole(), facultyId, loaded(e, duty)));

        DutySnapshot before = DutySnapshot.of(duty);
        duty.reassign(f);
        assignments.flush();
        audit.record("DUTY_REPLACED", ENTITY, e.chart().getId(), before, DutySnapshot.of(duty), blankToNull(reason));
        return refreshed(e);
    }

    /** Removes a duty; its position becomes open again, with reasons like any unfilled position. */
    @Transactional
    public ChartView remove(UUID periodId, UUID assignmentId, Long version, String reason) {
        Editing e = load(periodId, true);
        DutyAssignment duty = requireDuty(e, assignmentId);
        Versions.requireUnchanged(duty, version);

        DutySnapshot before = DutySnapshot.of(duty);
        SessionHall hall = duty.getSessionHall();
        Role role = duty.getRole();
        assignments.delete(duty);
        assignments.flush();
        List<Placement> after = e.duties().stream().filter(d -> !d.getId().equals(assignmentId))
                .map(AllocationService::placement).toList();
        var gap = unfilled.findByChartIdAndSessionHall_IdAndRole(e.chart().getId(), hall.getId(), role);
        int missing = gap.map(UnfilledPosition::getMissing).orElse(0) + 1;
        UnfilledPosition.Reasoning why = explainOpen(e, after, hall, role, missing);
        gap.ifPresentOrElse(g -> g.update(missing, why),
                () -> unfilled.save(new UnfilledPosition(e.chart().getId(), hall, role, missing, why)));
        unfilled.flush();
        audit.record("DUTY_REMOVED", ENTITY, e.chart().getId(), before, null, blankToNull(reason));
        return refreshed(e);
    }

    // ---- history ----

    /** Generation and every manual change of the period's current chart, newest first. */
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID periodId) {
        periods.findPeriod(periodId);
        DutyChart chart = charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.DRAFT)
                .or(() -> charts.findByPeriodIdOrderByChartVersionDesc(periodId).stream().findFirst())
                .orElseThrow(() -> ApiException.notFound("NO_CHART", "No duty chart has been generated for this period yet"));
        List<AuditEntry> entries = new ArrayList<>(auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(ENTITY, chart.getId()));
        auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("exam_period", periodId).stream()
                .filter(a -> "DUTY_CHART_GENERATED".equals(a.getAction()) && a.getAfterState() != null
                        && a.getAfterState().path("chartVersion").asInt() == chart.getChartVersion())
                .findFirst()
                .ifPresent(entries::add);
        entries.sort(Comparator.comparing(AuditEntry::getOccurredAt).reversed());
        Map<UUID, String> names = users.findAllById(entries.stream().map(AuditEntry::getActorUserId).filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        return entries.stream()
                .map(a -> new HistoryEntry(a.getId(), a.getOccurredAt(), a.getAction(), names.get(a.getActorUserId()),
                        summary(a), a.getReason()))
                .toList();
    }

    // ---- loading and lookups ----

    private Editing load(UUID periodId, boolean lock) {
        ExamPeriod period = periods.findPeriod(periodId);
        if (!period.allowsChartChanges()) {
            throw ApiException.conflict("PERIOD_LOCKED", "This examination period is " + period.getStatus()
                    + "; its duty chart can no longer be edited");
        }
        DutyChart chart = (lock ? charts.lockDraftForEdit(periodId) : charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.DRAFT))
                .orElseThrow(() -> ApiException.conflict("NO_DRAFT", "Only a draft duty chart can be edited. Generate one first."));
        List<DutyAssignment> duties = assignments.findForChart(chart.getId());
        Built built = allocation.buildForChart(period, chart);
        return new Editing(period, chart, built, duties, new ChartChecker(built.problem(), AllocationService.placements(duties)));
    }

    private ChartView refreshed(Editing e) {
        return allocation.view(e.chart(), e.period());
    }

    private static SessionHall requireHall(Editing e, UUID sessionHallId) {
        SessionHall hall = sessionHallId == null ? null : e.built().sessionHalls().get(sessionHallId);
        if (hall == null) throw ApiException.notFound("SESSION_HALL_NOT_FOUND", "That hall is not part of this examination period");
        return hall;
    }

    private static DutyAssignment requireDuty(Editing e, UUID assignmentId) {
        return e.duties().stream().filter(d -> d.getId().equals(assignmentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND", "That duty is not in the current draft chart"));
    }

    /** Active faculty of this college; other colleges' ids are 404 like everywhere else. */
    private Faculty requireActiveFaculty(Editing e, UUID facultyId) {
        if (facultyId == null) throw ApiException.unprocessable("FACULTY_REQUIRED", "Choose a faculty member");
        Faculty f = e.built().faculty().get(facultyId);
        if (f != null) return f;
        var detail = facultyService.get(facultyId);
        throw ApiException.unprocessable("FACULTY_INACTIVE", detail.fullName() + " is inactive and cannot be given duties");
    }

    /** The duty as the checker knows it, or null when it was skipped (inactive faculty). */
    private static Placement loaded(Editing e, DutyAssignment d) {
        return e.checker().isCandidate(d.getFaculty().getId()) ? AllocationService.placement(d) : null;
    }

    private int openCount(Editing e, SessionHall hall, Role role) {
        return unfilled.findByChartIdAndSessionHall_IdAndRole(e.chart().getId(), hall.getId(), role)
                .map(UnfilledPosition::getMissing).orElse(0);
    }

    private Set<UUID> pendingFor(Editing e, SessionHall hall) {
        UUID sessionId = hall.getSession().getId();
        return availability.periodOverview(e.period().getId()).stream()
                .filter(s -> s.session().id().equals(sessionId))
                .flatMap(s -> s.pending().stream().map(AvailabilityService.Ref::id))
                .collect(Collectors.toSet());
    }

    private static UnfilledPosition.Reasoning explainOpen(Editing e, List<Placement> placements, SessionHall hall, Role role,
                                                          int missing) {
        return AllocationService.reasoning(new ChartChecker(e.built().problem(), placements).explainOpen(hall.getId(), role, missing));
    }

    // ---- wording ----

    private static void requireAllowed(Faculty f, ChartChecker.Check check) {
        if (check.allowed()) return;
        String why = check.blocking().stream().map(r -> f.getFullName() + " " + r.singular()).collect(Collectors.joining("; "));
        throw ApiException.unprocessable("HARD_RULE_VIOLATED", "This change breaks a strict rule: " + why + ".");
    }

    private static Issue issue(Faculty f, Reason r) {
        return new Issue(r.name(), f.getFullName() + " " + r.singular() + ".");
    }

    private static Suggestion suggestion(Faculty f, ChartChecker.Alternative a, Set<UUID> pending) {
        List<String> warnings = new ArrayList<>(a.warnings());
        if (pending.contains(f.getId())) warnings.add("has a leave request for this session waiting for a decision");
        return new Suggestion(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getCode(), a.duties(),
                List.copyOf(warnings));
    }

    private static String roleName(Role role) {
        return role == Role.CHIEF ? "chief invigilator" : "invigilator";
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.strip().length() > 500) throw ApiException.unprocessable("REASON_TOO_LONG", "Keep the reason under 500 characters");
        return s.strip();
    }

    /** One line for the history list, from what the audit entry kept. */
    static String summary(AuditEntry a) {
        Function<JsonNode, String> who = n -> n.path("facultyName").asText() + " (" + n.path("facultyCode").asText() + ")";
        Function<JsonNode, String> where = n -> {
            String role = "CHIEF".equals(n.path("role").asText()) ? "chief invigilator" : "invigilator";
            String day = n.hasNonNull("date") ? LocalDate.parse(n.path("date").asText()).format(DAY) : "";
            return "as " + role + " in " + n.path("hallCode").asText() + ", " + day + " " + n.path("session").asText();
        };
        JsonNode before = a.getBeforeState();
        JsonNode after = a.getAfterState();
        return switch (a.getAction()) {
            case "DUTY_ASSIGNED" -> "Assigned " + who.apply(after) + " " + where.apply(after);
            case "DUTY_REPLACED" -> "Replaced " + who.apply(before) + " with " + who.apply(after) + " " + where.apply(after);
            case "DUTY_REMOVED" -> "Removed " + who.apply(before) + " " + where.apply(before).replaceFirst("^as (.+?) in ", "from ")
                    + " (" + ("CHIEF".equals(before.path("role").asText()) ? "chief invigilator" : "invigilator")
                    + "); the position is open";
            case "DUTY_HANDED_OVER" -> "Swap: " + who.apply(before.path(0)) + " handed over their duty "
                    + where.apply(before.path(0)) + " to " + who.apply(after.path(0));
            case "DUTY_SWAPPED" -> "Swap: " + who.apply(after.path(0)) + " now " + where.apply(after.path(0)) + "; "
                    + who.apply(after.path(1)) + " now " + where.apply(after.path(1));
            case "DUTY_SUBSTITUTED" -> "Substitute: " + who.apply(after) + " took over from " + who.apply(before) + " "
                    + where.apply(after);
            case "DUTY_CHART_PUBLISHED" -> "Published version " + after.path("chartVersion").asInt()
                    + (after.hasNonNull("supersededVersion") ? ", replacing version " + after.path("supersededVersion").asInt() : "")
                    + ": " + after.path("filled").asInt() + " of " + after.path("positions").asInt() + " positions filled";
            case "DUTY_CHART_DRAFTED" -> "Started draft version " + after.path("chartVersion").asInt() + " from published version "
                    + after.path("fromVersion").asInt();
            case "DUTY_CHART_GENERATED" -> "Generated draft version " + after.path("chartVersion").asInt() + ": "
                    + after.path("stats").path("filled").asInt() + " of " + after.path("stats").path("positions").asInt()
                    + " positions filled";
            default -> a.getAction();
        };
    }
}
