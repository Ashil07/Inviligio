package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationEngine;
import com.invigilo.allocation.engine.AllocationProblem;
import com.invigilo.allocation.engine.AllocationProblem.Candidate;
import com.invigilo.allocation.engine.AllocationProblem.HallSlot;
import com.invigilo.allocation.engine.AllocationProblem.SessionSlot;
import com.invigilo.allocation.engine.AllocationResult;
import com.invigilo.allocation.engine.AllocationResult.Explanation;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.ChartChecker;
import com.invigilo.allocation.engine.ProblemFingerprint;
import com.invigilo.audit.AuditService;
import com.invigilo.availability.AvailabilityService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.exam.Exam;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamSession;
import com.invigilo.exam.HallSeating;
import com.invigilo.exam.ScheduleService;
import com.invigilo.exam.SessionHall;
import com.invigilo.exam.Staffing;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyRestriction;
import com.invigilo.faculty.FacultyService;
import com.invigilo.policy.CollegePolicy;
import com.invigilo.policy.PolicyService;
import com.invigilo.policy.PolicySettings;
import com.invigilo.tenant.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the allocation problem from the database, runs the engine, stores the result as the
 * period's DRAFT chart (replacing any previous draft) and presents charts for review.
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    public record DutyView(UUID assignmentId, UUID facultyId, String facultyCode, String facultyName, String departmentCode,
                           Role role, DutyAssignment.Source source, Explanation explanation, long version) {}

    public record HallView(UUID sessionHallId, String hallCode, String hallName, int required, int missing,
                           List<DutyView> duties) {}

    public record SessionView(UUID id, LocalDate date, String label, LocalTime startTime, LocalTime endTime,
                              List<HallView> halls) {}

    public record UnfilledView(UUID sessionId, LocalDate date, String sessionLabel, UUID sessionHallId, String hallCode,
                               Role role, int missing, UnfilledPosition.Reasoning reason) {}

    public record WorkloadRow(UUID facultyId, String code, String name, String departmentCode, int duties, int chiefDuties,
                              BigDecimal dutyWeight, boolean active) {}

    /**
     * A duty in the chart that breaks a hard rule now, e.g. leave approved after generation.
     * {@code reasons} are {@link com.invigilo.allocation.engine.Reason} names, or {@code FACULTY_INACTIVE}.
     */
    public record IssueView(UUID assignmentId, LocalDate date, String sessionLabel, String hallCode, String facultyName,
                            List<String> reasons) {}

    public record ChartView(UUID id, UUID periodId, String periodName, int chartVersion, DutyChart.Status status,
                            Instant generatedAt, Integer policyVersion, boolean policyChanged, boolean inputsChanged,
                            AllocationResult.Stats stats, List<SessionView> sessions, List<UnfilledView> unfilled,
                            List<WorkloadRow> workload, List<IssueView> issues) {}

    /** The problem plus what is needed to store and explain its result. */
    record Built(AllocationProblem problem, CollegePolicy policy, Map<UUID, SessionHall> sessionHalls,
                 Map<UUID, Faculty> faculty) {}

    private final ExamPeriodService periods;
    private final ScheduleService schedule;
    private final FacultyService facultyService;
    private final AvailabilityService availability;
    private final PolicyService policies;
    private final AllocationRunRepository runs;
    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final UnfilledPositionRepository unfilled;
    private final AuditService audit;
    private final Clock clock;
    private final AllocationEngine engine = new AllocationEngine();

    public AllocationService(ExamPeriodService periods, ScheduleService schedule, FacultyService facultyService,
                             AvailabilityService availability, PolicyService policies, AllocationRunRepository runs,
                             DutyChartRepository charts, DutyAssignmentRepository assignments,
                             UnfilledPositionRepository unfilled, AuditService audit, Clock clock) {
        this.periods = periods;
        this.schedule = schedule;
        this.facultyService = facultyService;
        this.availability = availability;
        this.policies = policies;
        this.runs = runs;
        this.charts = charts;
        this.assignments = assignments;
        this.unfilled = unfilled;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- generation ----

    @Transactional
    public ChartView generate(UUID periodId, CurrentUser user) {
        ExamPeriod period = periods.findPeriod(periodId);
        if (!period.isEditable()) {
            throw ApiException.conflict("PERIOD_LOCKED", "This examination period is " + period.getStatus() + "; it can no longer be allocated");
        }
        Built built = build(period);
        if (built.problem().sessions().stream().allMatch(s -> s.halls().isEmpty())) {
            throw ApiException.unprocessable("NOTHING_TO_ALLOCATE", "No session of this period has halls with students yet");
        }
        Instant started = clock.instant();
        String hash = ProblemFingerprint.of(built.problem());
        UUID policyId = built.policy() == null ? null : built.policy().getId();
        AllocationResult result;
        try {
            result = engine.allocate(built.problem());
        } catch (RuntimeException e) {
            log.error("Allocation failed for period {}", periodId, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ALLOCATION_FAILED",
                    "Allocation could not be completed. Nothing was saved; please report this problem.");
        }
        AllocationRun run = runs.save(new AllocationRun(periodId, policyId, hash, started, user.userId()));
        run.completed(result.stats(), clock.instant());

        // Versions keep counting across replaced drafts, so "version 3" means the third generation.
        int nextVersion = charts.maxVersion(periodId) + 1;
        charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.DRAFT).ifPresent(old -> {
            charts.delete(old);
            charts.flush();
        });
        DutyChart chart = charts.saveAndFlush(new DutyChart(periodId, nextVersion, policyId, run.getId(), hash, user.userId()));
        List<DutyAssignment> rows = new ArrayList<>();
        for (AllocationResult.Assignment a : result.assignments()) {
            rows.add(new DutyAssignment(chart.getId(), built.sessionHalls().get(a.sessionHallId()), built.faculty().get(a.facultyId()),
                    a.role(), a.explanation()));
        }
        assignments.saveAll(rows);
        List<UnfilledPosition> gaps = new ArrayList<>();
        for (AllocationResult.Unfilled u : result.unfilled()) {
            gaps.add(new UnfilledPosition(chart.getId(), built.sessionHalls().get(u.sessionHallId()), u.role(), u.missing(),
                    reasoning(u)));
        }
        unfilled.saveAll(gaps);
        periods.markAllocating(periodId);
        assignments.flush();
        audit.record("DUTY_CHART_GENERATED", "exam_period", periodId, null,
                Map.of("chartVersion", chart.getChartVersion(), "stats", result.stats()), null);
        return view(chart, period);
    }

    // ---- review ----

    /** The DRAFT chart if there is one, otherwise the latest version. */
    @Transactional(readOnly = true)
    public ChartView current(UUID periodId) {
        ExamPeriod period = periods.findPeriod(periodId);
        DutyChart chart = charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.DRAFT)
                .or(() -> charts.findByPeriodIdOrderByChartVersionDesc(periodId).stream().findFirst())
                .orElseThrow(() -> ApiException.notFound("NO_CHART", "No duty chart has been generated for this period yet"));
        return view(chart, period);
    }

    ChartView view(DutyChart chart, ExamPeriod period) {
        List<DutyAssignment> duties = assignments.findForChart(chart.getId());
        List<UnfilledPosition> gaps = unfilled.findForChart(chart.getId());
        List<ExamSession> sessions = periods.sessionsOf(period.getId());

        Map<UUID, List<DutyAssignment>> bySessionHall = duties.stream()
                .collect(Collectors.groupingBy(d -> d.getSessionHall().getId()));
        Map<UUID, Integer> missingBySessionHall = new HashMap<>();
        gaps.forEach(g -> missingBySessionHall.merge(g.getSessionHall().getId(), g.getMissing(), Integer::sum));
        Map<UUID, List<SessionHall>> hallsBySession = schedule.sessionHallsFor(sessions.stream().map(ExamSession::getId).toList())
                .stream().collect(Collectors.groupingBy(sh -> sh.getSession().getId()));

        List<SessionView> sessionViews = new ArrayList<>();
        for (ExamSession s : sessions) {
            List<HallView> halls = new ArrayList<>();
            for (SessionHall sh : hallsBySession.getOrDefault(s.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(x -> x.getHall().getCode())).toList()) {
                List<DutyView> dv = bySessionHall.getOrDefault(sh.getId(), List.of()).stream()
                        .sorted(Comparator.comparing((DutyAssignment d) -> d.getRole()).thenComparing(d -> d.getFaculty().getFullName()))
                        .map(AllocationService::dutyView)
                        .toList();
                int missing = missingBySessionHall.getOrDefault(sh.getId(), 0);
                halls.add(new HallView(sh.getId(), sh.getHall().getCode(), sh.getHall().getName(), dv.size() + missing, missing, dv));
            }
            sessionViews.add(new SessionView(s.getId(), s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime(), halls));
        }

        List<UnfilledView> gapViews = gaps.stream()
                .map(g -> new UnfilledView(g.getSessionHall().getSession().getId(), g.getSessionHall().getSession().getSessionDate(),
                        g.getSessionHall().getSession().getLabel(), g.getSessionHall().getId(), g.getSessionHall().getHall().getCode(),
                        g.getRole(), g.getMissing(), g.getReason()))
                .sorted(Comparator.comparing(UnfilledView::date).thenComparing(UnfilledView::sessionLabel).thenComparing(UnfilledView::hallCode))
                .toList();

        Map<UUID, int[]> counts = new HashMap<>();
        Map<UUID, Faculty> people = new LinkedHashMap<>();
        for (FacultyService.AllocationFacts f : facultyService.allocationFacts()) people.put(f.faculty().getId(), f.faculty());
        for (DutyAssignment d : duties) {
            people.putIfAbsent(d.getFaculty().getId(), d.getFaculty());
            int[] c = counts.computeIfAbsent(d.getFaculty().getId(), k -> new int[2]);
            c[0]++;
            if (d.getRole() == Role.CHIEF) c[1]++;
        }
        List<WorkloadRow> workload = people.values().stream()
                .map(f -> {
                    int[] c = counts.getOrDefault(f.getId(), new int[2]);
                    return new WorkloadRow(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getCode(), c[0], c[1],
                            f.getDutyWeight(), f.isActive());
                })
                .sorted(Comparator.comparingInt(WorkloadRow::duties).reversed().thenComparing(WorkloadRow::name))
                .toList();

        AllocationRun run = chart.getAllocationRunId() == null ? null : runs.findById(chart.getAllocationRunId()).orElse(null);
        int activeFaculty = (int) workload.stream().filter(w -> w.active()).count();
        AllocationResult.Stats stats = liveStats(sessionViews, duties.size(), activeFaculty, workload,
                run == null || run.getStats() == null ? 0 : run.getStats().elapsedMillis());
        List<IssueView> issues = issues(duties, buildForChart(period, chart));
        var latestPolicy = policies.latest();
        UUID latestPolicyId = latestPolicy.map(CollegePolicy::getId).orElse(null);
        Integer policyVersion = latestPolicy.filter(p -> p.getId().equals(chart.getPolicyId())).map(CollegePolicy::getPolicyVersion)
                .orElse(null);
        boolean inputsChanged = chart.getStatus() == DutyChart.Status.DRAFT
                && !ProblemFingerprint.of(build(period).problem()).equals(chart.getInputHash());
        return new ChartView(chart.getId(), period.getId(), period.getName(), chart.getChartVersion(), chart.getStatus(),
                chart.getCreatedAt(), policyVersion, !Objects.equals(latestPolicyId, chart.getPolicyId()), inputsChanged,
                stats, sessionViews, gapViews, workload, issues);
    }

    private static DutyView dutyView(DutyAssignment d) {
        Faculty f = d.getFaculty();
        return new DutyView(d.getId(), f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getCode(), d.getRole(),
                d.getSource(), d.getExplanation(), d.getVersion());
    }

    /** Statistics of the chart as it is now (over active faculty), so they stay right after manual edits. */
    private static AllocationResult.Stats liveStats(List<SessionView> sessions, int filled, int activeFaculty,
                                                    List<WorkloadRow> workload, long elapsedMillis) {
        int positions = sessions.stream().flatMap(s -> s.halls().stream()).mapToInt(HallView::required).sum();
        int[] counts = workload.stream().filter(WorkloadRow::active).mapToInt(WorkloadRow::duties).toArray();
        int n = counts.length;
        int min = n == 0 ? 0 : java.util.Arrays.stream(counts).min().orElse(0);
        int max = java.util.Arrays.stream(counts).max().orElse(0);
        double mean = n == 0 ? 0 : java.util.Arrays.stream(counts).sum() / (double) n;
        double variance = n == 0 ? 0 : java.util.Arrays.stream(counts).mapToDouble(c -> Math.pow(c - mean, 2)).sum() / n;
        return new AllocationResult.Stats(positions, filled, activeFaculty, min, max, round2(mean), round2(Math.sqrt(variance)),
                elapsedMillis);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** Duties that break a hard rule against the rest of the chart, or belong to inactive faculty. */
    private static List<IssueView> issues(List<DutyAssignment> duties, Built built) {
        ChartChecker checker = new ChartChecker(built.problem(), placements(duties));
        Map<String, DutyAssignment> byPlacement = new HashMap<>();
        duties.forEach(d -> byPlacement.put(key(placement(d)), d));
        List<IssueView> out = new ArrayList<>();
        for (ChartChecker.Violation v : checker.violations()) {
            out.add(issue(byPlacement.get(key(v.placement())), v.reasons().stream().map(Enum::name).toList()));
        }
        for (ChartChecker.Placement p : checker.skipped()) out.add(issue(byPlacement.get(key(p)), List.of("FACULTY_INACTIVE")));
        out.sort(Comparator.comparing(IssueView::date).thenComparing(IssueView::sessionLabel).thenComparing(IssueView::hallCode)
                .thenComparing(IssueView::facultyName));
        return out;
    }

    private static IssueView issue(DutyAssignment d, List<String> reasons) {
        SessionHall sh = d.getSessionHall();
        return new IssueView(d.getId(), sh.getSession().getSessionDate(), sh.getSession().getLabel(), sh.getHall().getCode(),
                d.getFaculty().getFullName(), reasons);
    }

    private static String key(ChartChecker.Placement p) {
        return p.sessionHallId() + "/" + p.facultyId() + "/" + p.role();
    }

    static ChartChecker.Placement placement(DutyAssignment d) {
        return new ChartChecker.Placement(d.getSessionHall().getId(), d.getFaculty().getId(), d.getRole());
    }

    static List<ChartChecker.Placement> placements(List<DutyAssignment> duties) {
        return duties.stream().map(AllocationService::placement).toList();
    }

    /** How an unfilled position is stored. */
    static UnfilledPosition.Reasoning reasoning(AllocationResult.Unfilled u) {
        Map<String, Integer> eliminated = new LinkedHashMap<>();
        u.eliminated().forEach((reason, n) -> eliminated.put(reason.name(), n));
        return new UnfilledPosition.Reasoning(u.totalFaculty(), eliminated, u.suggestions());
    }

    // ---- building the problem ----

    private Built build(ExamPeriod period) {
        return build(period, policies.latest().orElse(null), false);
    }

    /**
     * The problem for checking edits to {@code chart}: the policy version it was generated with,
     * and every session hall of the period, including those that need nobody under current staffing.
     */
    Built buildForChart(ExamPeriod period, DutyChart chart) {
        CollegePolicy policy = policies.find(chart.getPolicyId()).or(policies::latest).orElse(null);
        return build(period, policy, true);
    }

    private Built build(ExamPeriod period, CollegePolicy policyRow, boolean includeEmptyHalls) {
        PolicySettings policy = policyRow == null ? PolicySettings.defaults() : policyRow.getSettings();
        List<ExamSession> sessions = periods.sessionsOf(period.getId());
        Map<UUID, List<SessionHall>> hallsBySession = schedule.sessionHallsFor(sessions.stream().map(ExamSession::getId).toList())
                .stream().collect(Collectors.groupingBy(sh -> sh.getSession().getId()));
        Map<UUID, SessionHall> sessionHalls = new HashMap<>();
        List<SessionSlot> slots = new ArrayList<>();
        for (ExamSession s : sessions) {
            List<HallSlot> halls = new ArrayList<>();
            for (SessionHall sh : hallsBySession.getOrDefault(s.getId(), List.of())) {
                Set<UUID> subjects = new HashSet<>();
                Set<UUID> departments = new HashSet<>();
                boolean practical = false;
                for (HallSeating seat : sh.getSeating()) {
                    Exam exam = seat.getExam();
                    subjects.add(exam.getSubject().getId());
                    departments.add(exam.getSubject().getDepartment().getId());
                    practical |= exam.getType() == Exam.Type.PRACTICAL;
                }
                Staffing staffing = Staffing.compute(policy, sh.getHall().getMinInvigilators(), sh.totalStudents(), practical,
                        sh.getRequiredOverride(), sh.getOverrideReason());
                if (staffing.total() == 0 && !includeEmptyHalls) continue;
                sessionHalls.put(sh.getId(), sh);
                halls.add(new HallSlot(sh.getId(), sh.getHall().getId(), sh.getHall().getCode(), Set.copyOf(subjects),
                        Set.copyOf(departments), staffing.invigilators(), staffing.chief()));
            }
            slots.add(new SessionSlot(s.getId(), s.getSessionDate(), s.getStartTime(), s.getEndTime(), s.getLabel(), halls));
        }

        Map<UUID, Set<UUID>> unavailable = availability.unavailabilityBySession(sessions);
        Map<UUID, Set<UUID>> unavailableByFaculty = new HashMap<>();
        unavailable.forEach((sessionId, ids) -> ids.forEach(f -> unavailableByFaculty.computeIfAbsent(f, k -> new HashSet<>()).add(sessionId)));
        Map<UUID, Long> history = new HashMap<>();
        for (Object[] row : assignments.historicalDuties(period.getId())) history.put((UUID) row[0], (Long) row[1]);

        Map<UUID, Faculty> people = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (FacultyService.AllocationFacts facts : facultyService.allocationFacts()) {
            Faculty f = facts.faculty();
            people.put(f.getId(), f);
            Set<UUID> excludedHalls = new HashSet<>();
            Set<UUID> excludedDepartments = new HashSet<>();
            boolean notChief = false;
            Integer maxDay = null;
            Integer maxPeriod = null;
            for (FacultyRestriction r : facts.restrictions()) {
                switch (r.getType()) {
                    case EXCLUDE_HALL -> excludedHalls.add(r.getHall().getId());
                    case EXCLUDE_DEPARTMENT_EXAMS -> excludedDepartments.add(r.getDepartment().getId());
                    case NOT_CHIEF -> notChief = true;
                    case MAX_DUTIES_PER_DAY -> maxDay = r.getMaxValue();
                    case MAX_DUTIES_PER_PERIOD -> maxPeriod = r.getMaxValue();
                }
            }
            boolean visiting = f.getEmploymentType() == Faculty.EmploymentType.VISITING
                    || f.getEmploymentType() == Faculty.EmploymentType.GUEST;
            candidates.add(new Candidate(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getId(), visiting,
                    f.isChiefEligible() && !notChief, f.getDutyWeight().doubleValue(), f.getJoinedOn(), Set.copyOf(facts.subjectIds()),
                    Set.copyOf(excludedHalls), Set.copyOf(excludedDepartments), maxDay, maxPeriod,
                    Set.copyOf(unavailableByFaculty.getOrDefault(f.getId(), Set.of())), history.getOrDefault(f.getId(), 0L)));
        }
        return new Built(new AllocationProblem(policy, slots, candidates), policyRow, sessionHalls, people);
    }

    /** Faculty with duties in any chart cannot be deleted. */
    @Component
    static class FacultyDuties implements UsageCheck<Faculty> {
        private final DutyAssignmentRepository assignments;

        FacultyDuties(DutyAssignmentRepository assignments) {
            this.assignments = assignments;
        }

        @Override
        public String usedBy() {
            return "duty assignment(s)";
        }

        @Override
        public long countUsages(UUID facultyId) {
            return assignments.countByFacultyId(facultyId);
        }
    }
}
