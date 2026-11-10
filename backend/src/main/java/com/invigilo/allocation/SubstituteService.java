package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.Built;
import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.allocation.ChartEditService.DutySnapshot;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.ChartChecker;
import com.invigilo.allocation.engine.ChartChecker.Placement;
import com.invigilo.audit.AuditService;
import com.invigilo.availability.AvailabilityService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Versions;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamSession;
import com.invigilo.exam.SessionHall;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyService;
import com.invigilo.policy.PolicyService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emergency substitutes (M14): when someone cannot do a duty of the PUBLISHED chart, the exam cell
 * picks a replacement from a ranked list. Candidates and the final check come from
 * {@link ChartChecker}, i.e. the hard rules and soft costs of generation, so a substitute never
 * breaks a strict rule. The duty is handed over in place (source SUBSTITUTE), so the substitute gets
 * the duty and its credit; the change is audited on the chart with the reason. Allowed only while
 * the policy lets the exam cell reassign directly, and until the session has ended.
 */
@Service
public class SubstituteService {

    static final int CANDIDATES = 15;

    public record DutyInfo(UUID assignmentId, LocalDate date, String sessionLabel, LocalTime startTime, LocalTime endTime,
                           String hallCode, String hallName, Role role, UUID facultyId, String facultyName, long version) {}

    /** Someone who could take the duty, best first, with what is worth knowing about choosing them. */
    public record Candidate(UUID facultyId, String code, String name, String departmentCode, int duties, List<String> notes) {}

    /**
     * {@code allowed}: whether the policy lets the exam cell reassign directly. {@code excluded}: why
     * the rest of the faculty cannot take it (the same funnel as unfilled positions).
     */
    public record Options(DutyInfo duty, boolean allowed, List<Candidate> candidates, UnfilledPosition.Reasoning excluded) {}

    /** The published chart of a period and everything needed to check changes to it. */
    private record Published(ExamPeriod period, DutyChart chart, Built built, List<DutyAssignment> duties, ChartChecker checker) {}

    private final AllocationService allocation;
    private final ExamPeriodService periods;
    private final FacultyService facultyService;
    private final AvailabilityService availability;
    private final PolicyService policies;
    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final SwapService swaps;
    private final CollegeTime collegeTime;
    private final AuditService audit;

    SubstituteService(AllocationService allocation, ExamPeriodService periods, FacultyService facultyService,
                      AvailabilityService availability, PolicyService policies, DutyChartRepository charts,
                      DutyAssignmentRepository assignments, SwapService swaps, CollegeTime collegeTime, AuditService audit) {
        this.allocation = allocation;
        this.periods = periods;
        this.facultyService = facultyService;
        this.availability = availability;
        this.policies = policies;
        this.charts = charts;
        this.assignments = assignments;
        this.swaps = swaps;
        this.collegeTime = collegeTime;
        this.audit = audit;
    }

    /** Who could take over a duty of the published chart, best first. */
    @Transactional(readOnly = true)
    public Options options(UUID periodId, UUID assignmentId) {
        Published p = load(periodId, false);
        DutyAssignment duty = requireDuty(p, assignmentId);
        requireNotEnded(duty);
        SessionHall hall = duty.getSessionHall();
        Placement seat = loaded(p, duty);
        Set<UUID> pending = pendingFor(p, hall);
        List<Candidate> candidates = p.checker().alternatives(hall.getId(), duty.getRole(), seat, CANDIDATES).stream()
                .map(a -> {
                    Faculty f = p.built().faculty().get(a.facultyId());
                    List<String> notes = new ArrayList<>(a.warnings());
                    if (pending.contains(f.getId())) notes.add("has a leave request for this session waiting for a decision");
                    return new Candidate(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getCode(), a.duties(),
                            List.copyOf(notes));
                })
                .toList();
        UnfilledPosition.Reasoning excluded = AllocationService.reasoning(p.checker().explainOpen(hall.getId(), duty.getRole(), 1));
        return new Options(info(duty), allowed(), candidates, excluded);
    }

    /**
     * Gives the duty to {@code facultyId}. {@code version} is the duty's version when the list was
     * loaded; {@code reason} (required) says why, e.g. "absent: illness".
     */
    @Transactional
    public ChartView substitute(UUID periodId, UUID assignmentId, UUID facultyId, Long version, String reason) {
        if (!allowed()) {
            throw ApiException.unprocessable("DIRECT_REASSIGNMENT_DISABLED",
                    "The college policy does not let the examination cell reassign published duties directly");
        }
        String why = requireReason(reason);
        Published p = load(periodId, true);
        DutyAssignment duty = requireDuty(p, assignmentId);
        Versions.requireUnchanged(duty, version);
        requireNotEnded(duty);
        if (facultyId == null) throw ApiException.unprocessable("FACULTY_REQUIRED", "Choose the substitute");
        if (duty.getFaculty().getId().equals(facultyId)) {
            throw ApiException.unprocessable("SAME_FACULTY", duty.getFaculty().getFullName() + " already has this duty");
        }
        Faculty substitute = p.built().faculty().get(facultyId);
        if (substitute == null) {
            Faculty inactive = facultyService.find(facultyId); // 404 for unknown or other-college ids
            throw ApiException.unprocessable("FACULTY_INACTIVE", inactive.getFullName() + " is inactive and cannot be given duties");
        }
        ChartChecker.Check check = p.checker().check(duty.getSessionHall().getId(), duty.getRole(), facultyId, loaded(p, duty));
        if (!check.allowed()) {
            String rules = check.blocking().stream().map(r -> substitute.getFullName() + " " + r.singular()).collect(Collectors.joining("; "));
            throw ApiException.unprocessable("HARD_RULE_VIOLATED", "This substitute breaks a strict rule: " + rules + ".");
        }

        DutySnapshot before = DutySnapshot.of(duty);
        duty.substitute(substitute);
        assignments.flush();
        audit.record("DUTY_SUBSTITUTED", ChartEditService.ENTITY, p.chart().getId(), before, DutySnapshot.of(duty), why);
        swaps.invalidateOpenFor(duty, "The examination cell gave this duty to " + substitute.getFullName() + " as a substitute.");
        return allocation.view(p.chart(), p.period());
    }

    // ---- lookups and rules ----

    private Published load(UUID periodId, boolean lock) {
        ExamPeriod period = periods.findPeriod(periodId);
        if (!period.allowsChartChanges()) {
            throw ApiException.conflict("PERIOD_LOCKED", "This examination period is closed; its duties can no longer change");
        }
        DutyChart chart = charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.PUBLISHED)
                .orElseThrow(() -> ApiException.conflict("NO_PUBLISHED_CHART", "Substitutes are chosen for the published duty chart; publish one first"));
        if (lock) chart = charts.lockById(chart.getId()).orElseThrow();
        List<DutyAssignment> duties = assignments.findForChart(chart.getId()).stream()
                .filter(d -> d.getStatus() != DutyAssignment.Status.CANCELLED)
                .toList();
        Built built = allocation.buildForChart(period, chart);
        return new Published(period, chart, built, duties, new ChartChecker(built.problem(), AllocationService.placements(duties)));
    }

    private static DutyAssignment requireDuty(Published p, UUID assignmentId) {
        return p.duties().stream().filter(d -> d.getId().equals(assignmentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND", "That duty is not in the published duty chart"));
    }

    /** The duty as the checker knows it, or null when its holder is no longer active (then a vacant seat is used). */
    private static Placement loaded(Published p, DutyAssignment d) {
        return p.checker().isCandidate(d.getFaculty().getId()) ? AllocationService.placement(d) : null;
    }

    private void requireNotEnded(DutyAssignment duty) {
        ExamSession s = duty.getSessionHall().getSession();
        if (!s.getSessionDate().atTime(s.getEndTime()).isAfter(collegeTime.now())) {
            throw ApiException.conflict("DUTY_ENDED", "This session has already ended; its duties can no longer change");
        }
    }

    private Set<UUID> pendingFor(Published p, SessionHall hall) {
        UUID sessionId = hall.getSession().getId();
        return availability.periodOverview(p.period().getId()).stream()
                .filter(s -> s.session().id().equals(sessionId))
                .flatMap(s -> s.pending().stream().map(AvailabilityService.Ref::id))
                .collect(Collectors.toSet());
    }

    private boolean allowed() {
        return policies.current().settings().swaps().adminCanReassignDirectly();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("REASON_REQUIRED", "Say why the duty needs a substitute, e.g. \"absent: illness\"");
        }
        if (reason.strip().length() > 500) throw ApiException.unprocessable("REASON_TOO_LONG", "Keep the reason under 500 characters");
        return reason.strip();
    }

    private static DutyInfo info(DutyAssignment d) {
        SessionHall sh = d.getSessionHall();
        ExamSession s = sh.getSession();
        return new DutyInfo(d.getId(), s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime(), sh.getHall().getCode(),
                sh.getHall().getName(), d.getRole(), d.getFaculty().getId(), d.getFaculty().getFullName(), d.getVersion());
    }
}
