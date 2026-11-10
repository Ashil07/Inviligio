package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.ChartChecker;
import com.invigilo.allocation.engine.ChartChecker.Load;
import com.invigilo.common.error.ApiException;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fairness analytics (M15), from the duties of PUBLISHED charts (what really happens, swaps and
 * substitutes included; drafts never count). Per-person figures are over the college's active
 * faculty, as in the chart's own statistics, and over/under uses the engine's rule
 * {@link ChartChecker#load}. Cancelled duties never count.
 */
@Service
public class FairnessService {

    public record PeriodOption(UUID id, String name, LocalDate startDate, int publishedVersion) {}

    /** Over the active faculty; {@code withoutDuty} is how many of them have none. */
    public record Summary(int positions, int filled, int unfilled, int activeFaculty, int duties, double mean, int min, int max,
                          double stdDev, int over, int under, int withoutDuty) {}

    /** {@code load} is null for inactive faculty; {@code allPeriods} counts duties in every published chart. */
    public record FacultyRow(UUID facultyId, String code, String name, String departmentCode, boolean active, BigDecimal dutyWeight,
                             int duties, int chiefDuties, double fairShare, Load load, int allPeriods) {}

    public record DepartmentRow(String code, String name, int faculty, int duties, double perPerson, int min, int max, int over,
                                int under) {}

    /** One published period, oldest first. */
    public record HistoryPoint(UUID periodId, String name, LocalDate startDate, int positions, int filled, double mean, double stdDev,
                               int min, int max) {}

    public record UnfilledRow(LocalDate date, String sessionLabel, String hallCode, Role role, int missing,
                              Map<String, Integer> eliminated) {}

    /** Period fields are null until something is published. */
    public record Fairness(List<PeriodOption> periods, UUID periodId, String periodName, Integer chartVersion, Summary summary,
                           List<FacultyRow> faculty, List<DepartmentRow> departments, List<HistoryPoint> history,
                           List<UnfilledRow> unfilled) {}

    /** The figures of a set of duty counts. */
    private record Stats(int n, int total, double mean, int min, int max, double stdDev) {
        static Stats of(List<Integer> counts) {
            int n = counts.size();
            int total = counts.stream().mapToInt(Integer::intValue).sum();
            double mean = n == 0 ? 0 : total / (double) n;
            int min = counts.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
            double variance = n == 0 ? 0 : counts.stream().mapToDouble(c -> Math.pow(c - mean, 2)).sum() / n;
            return new Stats(n, total, mean, min, max, Math.sqrt(variance));
        }
    }

    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final UnfilledPositionRepository unfilled;
    private final ExamPeriodService periods;
    private final FacultyService facultyService;

    FairnessService(DutyChartRepository charts, DutyAssignmentRepository assignments, UnfilledPositionRepository unfilled,
                    ExamPeriodService periods, FacultyService facultyService) {
        this.charts = charts;
        this.assignments = assignments;
        this.unfilled = unfilled;
        this.periods = periods;
        this.facultyService = facultyService;
    }

    /** {@code periodId} null picks the published period that starts last. */
    @Transactional(readOnly = true)
    public Fairness fairness(UUID periodId) {
        Map<UUID, DutyChart> published = charts.findByStatus(DutyChart.Status.PUBLISHED).stream()
                .collect(Collectors.toMap(DutyChart::getPeriodId, c -> c, (a, b) -> a));
        List<ExamPeriodService.PeriodView> withChart = periods.list().stream() // newest first
                .filter(p -> published.containsKey(p.id())).toList();
        List<PeriodOption> options = withChart.stream()
                .map(p -> new PeriodOption(p.id(), p.name(), p.startDate(), published.get(p.id()).getChartVersion())).toList();
        Optional<ExamPeriodService.PeriodView> selected = periodId == null ? withChart.stream().findFirst()
                : withChart.stream().filter(p -> p.id().equals(periodId)).findFirst();
        if (periodId != null && selected.isEmpty()) {
            throw ApiException.notFound("PUBLISHED_CHART_NOT_FOUND", "No published duty chart for this exam period");
        }

        List<Faculty> active = facultyService.allocationFacts().stream().map(FacultyService.AllocationFacts::faculty).toList();
        // Every published chart's duties, for the history and the all-periods totals.
        Map<UUID, List<DutyAssignment>> dutiesByPeriod = new HashMap<>();
        Map<UUID, Integer> allPeriods = new HashMap<>();
        for (ExamPeriodService.PeriodView p : withChart) {
            List<DutyAssignment> duties = assignments.findForChart(published.get(p.id()).getId()).stream()
                    .filter(d -> d.getStatus() != DutyAssignment.Status.CANCELLED).toList();
            dutiesByPeriod.put(p.id(), duties);
            duties.forEach(d -> allPeriods.merge(d.getFaculty().getId(), 1, Integer::sum));
        }

        List<HistoryPoint> history = new ArrayList<>();
        for (ExamPeriodService.PeriodView p : withChart.reversed()) {
            List<DutyAssignment> duties = dutiesByPeriod.get(p.id());
            Stats s = Stats.of(countsFor(active, duties));
            int missing = unfilled.missingIn(published.get(p.id()).getId());
            history.add(new HistoryPoint(p.id(), p.name(), p.startDate(), duties.size() + missing, duties.size(), round2(s.mean()),
                    round2(s.stdDev()), s.min(), s.max()));
        }

        if (selected.isEmpty()) {
            List<FacultyRow> rows = active.stream().sorted(Comparator.comparing(Faculty::getFullName))
                    .map(f -> row(f, 0, 0, 0, 0)).toList();
            return new Fairness(options, null, null, null, null, rows, departments(rows, active), history, List.of());
        }

        ExamPeriodService.PeriodView period = selected.get();
        DutyChart chart = published.get(period.id());
        List<DutyAssignment> duties = dutiesByPeriod.get(period.id());
        Map<UUID, int[]> counts = new HashMap<>();
        Map<UUID, Faculty> people = new LinkedHashMap<>();
        active.forEach(f -> people.put(f.getId(), f));
        for (DutyAssignment d : duties) {
            people.putIfAbsent(d.getFaculty().getId(), d.getFaculty()); // inactive now, but had duties
            int[] c = counts.computeIfAbsent(d.getFaculty().getId(), k -> new int[2]);
            c[0]++;
            if (d.getRole() == Role.CHIEF) c[1]++;
        }
        Stats s = Stats.of(countsFor(active, duties));
        List<FacultyRow> rows = people.values().stream()
                .map(f -> {
                    int[] c = counts.getOrDefault(f.getId(), new int[2]);
                    return row(f, c[0], c[1], s.mean(), allPeriods.getOrDefault(f.getId(), 0));
                })
                .sorted(Comparator.comparingInt(FacultyRow::duties).reversed().thenComparing(FacultyRow::name))
                .toList();

        List<UnfilledPosition> gaps = unfilled.findForChart(chart.getId());
        int missing = gaps.stream().mapToInt(UnfilledPosition::getMissing).sum();
        int over = (int) rows.stream().filter(r -> r.load() == Load.OVER).count();
        int under = (int) rows.stream().filter(r -> r.load() == Load.UNDER).count();
        int withoutDuty = (int) rows.stream().filter(r -> r.active() && r.duties() == 0).count();
        Summary summary = new Summary(duties.size() + missing, duties.size(), missing, s.n(), duties.size(), round2(s.mean()), s.min(),
                s.max(), round2(s.stdDev()), over, under, withoutDuty);
        List<UnfilledRow> gapRows = gaps.stream()
                .map(g -> new UnfilledRow(g.getSessionHall().getSession().getSessionDate(), g.getSessionHall().getSession().getLabel(),
                        g.getSessionHall().getHall().getCode(), g.getRole(), g.getMissing(), g.getReason().eliminated()))
                .sorted(Comparator.comparing(UnfilledRow::date).thenComparing(UnfilledRow::sessionLabel).thenComparing(UnfilledRow::hallCode))
                .toList();
        return new Fairness(options, period.id(), period.name(), chart.getChartVersion(), summary, rows, departments(rows, active),
                history, gapRows);
    }

    /** Duties per active faculty member (zero included) in one chart. */
    private static List<Integer> countsFor(List<Faculty> active, List<DutyAssignment> duties) {
        Map<UUID, Integer> byFaculty = new HashMap<>();
        duties.forEach(d -> byFaculty.merge(d.getFaculty().getId(), 1, Integer::sum));
        return active.stream().map(f -> byFaculty.getOrDefault(f.getId(), 0)).toList();
    }

    private static FacultyRow row(Faculty f, int duties, int chief, double mean, int allPeriods) {
        double weight = f.getDutyWeight().doubleValue();
        return new FacultyRow(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getCode(), f.isActive(),
                f.getDutyWeight(), duties, chief, f.isActive() ? round2(mean * weight) : 0,
                f.isActive() ? ChartChecker.load(duties, mean, weight) : null, allPeriods);
    }

    /** Active faculty per department, busiest per person first. */
    private static List<DepartmentRow> departments(List<FacultyRow> rows, List<Faculty> active) {
        Map<String, String> names = new HashMap<>();
        active.forEach(f -> names.put(f.getDepartment().getCode(), f.getDepartment().getName()));
        return rows.stream().filter(FacultyRow::active)
                .collect(Collectors.groupingBy(FacultyRow::departmentCode, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> {
                    Stats s = Stats.of(e.getValue().stream().map(FacultyRow::duties).toList());
                    int over = (int) e.getValue().stream().filter(r -> r.load() == Load.OVER).count();
                    int under = (int) e.getValue().stream().filter(r -> r.load() == Load.UNDER).count();
                    return new DepartmentRow(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()), s.n(), s.total(), round2(s.mean()),
                            s.min(), s.max(), over, under);
                })
                .sorted(Comparator.comparingDouble(DepartmentRow::perPerson).reversed().thenComparing(DepartmentRow::code))
                .toList();
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
