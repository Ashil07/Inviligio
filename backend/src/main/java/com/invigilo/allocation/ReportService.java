package com.invigilo.allocation;

import com.invigilo.allocation.FairnessService.Fairness;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.college.CollegeService;
import com.invigilo.common.error.ApiException;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamSession;
import com.invigilo.exam.SessionHall;
import com.invigilo.report.Report;
import com.invigilo.report.Report.Column;
import com.invigilo.report.Report.Section;
import com.invigilo.report.ReportFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report downloads (M16): the duty chart grouped by date, session, hall, faculty or department, the
 * workload of every faculty member, and the fairness figures, as PDF, Excel or CSV. Built from the
 * same data as the screens (the published chart unless a version is asked for; the fairness figures
 * from {@link FairnessService}).
 */
@Service
public class ReportService {

    public enum GroupBy { DATE, SESSION, HALL, FACULTY, DEPARTMENT }

    /** Optional filters of the duty chart report; null means "all". */
    public record Filters(String department, LocalDate from, LocalDate to, String session, String hall) {
        boolean any() {
            return department != null || from != null || to != null || session != null || hall != null;
        }
    }

    public record ReportFile(String filename, ReportFormat format, byte[] content) {}

    /** One line of the duty chart: a duty, or an open position ({@code facultyName} null). */
    private record Line(LocalDate date, String session, LocalTime start, LocalTime end, String hallCode, String hallName, Role role,
                        String facultyCode, String facultyName, String department) {
        boolean open() {
            return facultyName == null;
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final UnfilledPositionRepository unfilled;
    private final ExamPeriodService periods;
    private final FairnessService fairness;
    private final RemunerationService remuneration;
    private final CollegeService colleges;
    private final CollegeTime collegeTime;

    ReportService(DutyChartRepository charts, DutyAssignmentRepository assignments, UnfilledPositionRepository unfilled,
                  ExamPeriodService periods, FairnessService fairness, RemunerationService remuneration, CollegeService colleges,
                  CollegeTime collegeTime) {
        this.charts = charts;
        this.assignments = assignments;
        this.unfilled = unfilled;
        this.periods = periods;
        this.fairness = fairness;
        this.remuneration = remuneration;
        this.colleges = colleges;
        this.collegeTime = collegeTime;
    }

    // ---- duty chart ----

    /** The duty chart of a period: the published version, or {@code version} when given (e.g. a draft under review). */
    @Transactional(readOnly = true)
    public ReportFile dutyChart(UUID periodId, Integer version, GroupBy groupBy, Filters filters, ReportFormat format) {
        ExamPeriod period = periods.findPeriod(periodId);
        DutyChart chart = (version == null
                ? charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.PUBLISHED)
                : charts.findByPeriodIdAndChartVersion(periodId, version))
                .orElseThrow(() -> version == null
                        ? ApiException.conflict("NO_PUBLISHED_CHART", "No duty chart is published for this period yet; choose a version")
                        : ApiException.notFound("CHART_VERSION_NOT_FOUND", "This period has no chart version " + version));
        GroupBy group = groupBy == null ? GroupBy.DATE : groupBy;

        List<Line> lines = new ArrayList<>();
        for (DutyAssignment d : assignments.findForChart(chart.getId())) {
            if (d.getStatus() == DutyAssignment.Status.CANCELLED) continue;
            SessionHall sh = d.getSessionHall();
            ExamSession s = sh.getSession();
            lines.add(new Line(s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime(), sh.getHall().getCode(),
                    sh.getHall().getName(), d.getRole(), d.getFaculty().getEmployeeCode(), d.getFaculty().getFullName(),
                    d.getFaculty().getDepartment().getCode()));
        }
        // Open positions belong on a chart by date, session or hall, so nobody forgets them.
        boolean withOpen = filters.department() == null && (group == GroupBy.DATE || group == GroupBy.SESSION || group == GroupBy.HALL);
        if (withOpen) {
            for (UnfilledPosition u : unfilled.findForChart(chart.getId())) {
                SessionHall sh = u.getSessionHall();
                ExamSession s = sh.getSession();
                for (int i = 0; i < u.getMissing(); i++) {
                    lines.add(new Line(s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime(), sh.getHall().getCode(),
                            sh.getHall().getName(), u.getRole(), null, null, null));
                }
            }
        }
        List<Line> shown = lines.stream().filter(l -> matches(l, filters))
                .sorted(Comparator.comparing(Line::date).thenComparing(Line::start).thenComparing(Line::hallCode)
                        .thenComparing(Line::role).thenComparing(l -> l.open() ? "~" : l.facultyName()))
                .toList();

        long duties = shown.stream().filter(l -> !l.open()).count();
        long open = shown.stream().filter(Line::open).count();
        List<String> details = new ArrayList<>(header(period));
        details.add("Duty chart version " + chart.getChartVersion() + " (" + chart.getStatus().name().toLowerCase(Locale.ROOT) + ")"
                + ", grouped by " + group.name().toLowerCase(Locale.ROOT));
        if (filters.any()) details.add("Filters: " + describe(filters));
        details.add(duties + (duties == 1 ? " duty" : " duties") + (withOpen ? ", " + open + " open position" + (open == 1 ? "" : "s") : ""));

        Report report = switch (group) {
            case DATE -> grouped("Duty chart by date", details, "Date", shown, l -> DAY.format(l.date()),
                    List.of(Column.text("Session"), Column.text("Time"), Column.text("Hall"), Column.text("Role"), Column.text("Faculty"),
                            Column.text("Code"), Column.text("Department")),
                    l -> List.of(l.session(), time(l), l.hallCode(), role(l), name(l), code(l), dept(l)));
            case SESSION -> grouped("Duty chart by session", details, "Session", shown,
                    l -> DAY.format(l.date()) + " · " + l.session() + " " + time(l),
                    List.of(Column.text("Hall"), Column.text("Hall name"), Column.text("Role"), Column.text("Faculty"), Column.text("Code"),
                            Column.text("Department")),
                    l -> List.of(l.hallCode(), l.hallName(), role(l), name(l), code(l), dept(l)));
            case HALL -> grouped("Duty chart by hall", details, "Hall",
                    shown.stream().sorted(Comparator.comparing(Line::hallCode)).toList(), l -> l.hallCode() + " · " + l.hallName(),
                    List.of(Column.text("Date"), Column.text("Session"), Column.text("Time"), Column.text("Role"), Column.text("Faculty"),
                            Column.text("Code"), Column.text("Department")),
                    l -> List.of(DAY.format(l.date()), l.session(), time(l), role(l), name(l), code(l), dept(l)));
            case FACULTY -> grouped("Duty chart by faculty", details, "Faculty",
                    shown.stream().sorted(Comparator.comparing(Line::facultyName).thenComparing(Line::facultyCode)).toList(),
                    l -> l.facultyName() + " (" + l.facultyCode() + ") · " + l.department(),
                    List.of(Column.text("Date"), Column.text("Session"), Column.text("Time"), Column.text("Hall"), Column.text("Role")),
                    l -> List.of(DAY.format(l.date()), l.session(), time(l), l.hallCode(), role(l)));
            case DEPARTMENT -> grouped("Duty chart by department", details, "Department",
                    shown.stream().sorted(Comparator.comparing(Line::department)).toList(), Line::department,
                    List.of(Column.text("Faculty"), Column.text("Code"), Column.text("Date"), Column.text("Session"), Column.text("Time"),
                            Column.text("Hall"), Column.text("Role")),
                    l -> List.of(l.facultyName(), l.facultyCode(), DAY.format(l.date()), l.session(), time(l), l.hallCode(), role(l)));
        };
        return file(report, "duty-chart-" + slug(period.getName()) + "-by-" + group.name().toLowerCase(Locale.ROOT), format);
    }

    private static boolean matches(Line l, Filters f) {
        return (f.department() == null || f.department().equalsIgnoreCase(l.department()))
                && (f.from() == null || !l.date().isBefore(f.from()))
                && (f.to() == null || !l.date().isAfter(f.to()))
                && (f.session() == null || f.session().equalsIgnoreCase(l.session()))
                && (f.hall() == null || f.hall().equalsIgnoreCase(l.hallCode()));
    }

    /** Sections in the order the lines first reach them (the lines come sorted). */
    private static Report grouped(String title, List<String> details, String groupLabel, List<Line> lines, Function<Line, String> key,
                                  List<Column> columns, Function<Line, List<String>> row) {
        Map<String, List<List<String>>> sections = new LinkedHashMap<>();
        for (Line l : lines) sections.computeIfAbsent(key.apply(l), k -> new ArrayList<>()).add(row.apply(l));
        List<Section> out = sections.entrySet().stream().map(e -> new Section(e.getKey(), columns, e.getValue())).toList();
        return new Report(title, details, groupLabel, out.isEmpty() ? List.of(new Section(null, columns, List.of())) : out);
    }

    // ---- workload and fairness ----

    /** Every faculty member's duties in the period's published chart against an even share. */
    @Transactional(readOnly = true)
    public ReportFile workload(UUID periodId, String department, ReportFormat format) {
        Fairness f = fairness.fairness(periodId);
        List<String> details = new ArrayList<>(header(periods.findPeriod(periodId)));
        details.add("Published duty chart version " + f.chartVersion() + "; per person " + f.summary().mean() + " duties on average (range "
                + f.summary().min() + "-" + f.summary().max() + ", spread " + f.summary().stdDev() + ")");
        if (department != null) details.add("Filters: department " + department);
        List<List<String>> rows = f.faculty().stream()
                .filter(r -> department == null || department.equalsIgnoreCase(r.departmentCode()))
                .map(r -> List.of(r.name(), r.code(), r.departmentCode(), r.dutyWeight().stripTrailingZeros().toPlainString(),
                        String.valueOf(r.duties()), String.valueOf(r.chiefDuties()), r.active() ? String.valueOf(r.fairShare()) : "",
                        r.load() == null ? "Inactive" : switch (r.load()) {
                            case OVER -> "Over";
                            case UNDER -> "Under";
                            case BALANCED -> "Balanced";
                        },
                        String.valueOf(r.allPeriods())))
                .toList();
        Report report = new Report("Faculty workload", details, null, List.of(new Section(null, List.of(Column.text("Faculty"),
                Column.text("Code"), Column.text("Department"), Column.number("Duty weight"), Column.number("Duties"),
                Column.number("As chief"), Column.number("Even share"), Column.text("Workload"), Column.number("All periods")), rows)));
        return file(report, "workload-" + slug(f.periodName()), format);
    }

    /** The fairness figures: summary, departments, history across periods and open positions. */
    @Transactional(readOnly = true)
    public ReportFile fairness(UUID periodId, ReportFormat format) {
        Fairness f = fairness.fairness(periodId);
        var s = f.summary();
        List<String> details = new ArrayList<>(header(periods.findPeriod(periodId)));
        details.add("From published duty chart version " + f.chartVersion() + "; per-person figures count every active faculty member");
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Summary", List.of(Column.text("Measure"), Column.number("Value")), List.of(
                List.of("Positions", String.valueOf(s.positions())),
                List.of("Filled", String.valueOf(s.filled())),
                List.of("Open", String.valueOf(s.unfilled())),
                List.of("Active faculty", String.valueOf(s.activeFaculty())),
                List.of("Duties per person (average)", String.valueOf(s.mean())),
                List.of("Fewest duties", String.valueOf(s.min())),
                List.of("Most duties", String.valueOf(s.max())),
                List.of("Spread (standard deviation)", String.valueOf(s.stdDev())),
                List.of("Over an even share", String.valueOf(s.over())),
                List.of("Under an even share", String.valueOf(s.under())),
                List.of("Active faculty without a duty", String.valueOf(s.withoutDuty())))));
        sections.add(new Section("Departments", List.of(Column.text("Department"), Column.text("Name"), Column.number("Faculty"),
                Column.number("Duties"), Column.number("Per person"), Column.number("Fewest"), Column.number("Most"), Column.number("Over"),
                Column.number("Under")),
                f.departments().stream().map(d -> List.of(d.code(), d.name(), String.valueOf(d.faculty()), String.valueOf(d.duties()),
                        String.valueOf(d.perPerson()), String.valueOf(d.min()), String.valueOf(d.max()), String.valueOf(d.over()),
                        String.valueOf(d.under()))).toList()));
        sections.add(new Section("Across exam periods", List.of(Column.text("Exam period"), Column.text("Starts"), Column.number("Positions"),
                Column.number("Filled"), Column.number("Per person"), Column.number("Spread"), Column.number("Fewest"), Column.number("Most")),
                f.history().stream().map(h -> List.of(h.name(), DAY.format(h.startDate()), String.valueOf(h.positions()),
                        String.valueOf(h.filled()), String.valueOf(h.mean()), String.valueOf(h.stdDev()), String.valueOf(h.min()),
                        String.valueOf(h.max()))).toList()));
        if (!f.unfilled().isEmpty()) {
            sections.add(new Section("Open positions", List.of(Column.text("Date"), Column.text("Session"), Column.text("Hall"),
                    Column.text("Role"), Column.number("Open"), Column.text("Why nobody could take it")),
                    f.unfilled().stream().map(u -> List.of(DAY.format(u.date()), u.sessionLabel(), u.hallCode(),
                            u.role() == Role.CHIEF ? "Chief invigilator" : "Invigilator", String.valueOf(u.missing()),
                            u.eliminated().entrySet().stream().map(e -> e.getValue() + " " + e.getKey().toLowerCase(Locale.ROOT).replace('_', ' '))
                                    .collect(Collectors.joining(", ")))).toList()));
        }
        return file(new Report("Fairness of duty allocation", details, null, sections), "fairness-" + slug(f.periodName()), format);
    }

    /** Pay per faculty member and department from completed duties, with the rates used. */
    @Transactional(readOnly = true)
    public ReportFile remuneration(UUID periodId, String department, ReportFormat format) {
        RemunerationService.Pay pay = remuneration.pay(periodId);
        var s = pay.summary();
        List<String> details = new ArrayList<>(header(periods.findPeriod(periodId)));
        details.add("Published duty chart version " + pay.chartVersion() + "; a duty is paid once its session has ended unless the person was"
                + " marked absent. Amounts in " + s.currency() + ".");
        if (department != null) details.add("Filters: department " + department);
        if (s.withoutRate() > 0) details.add(s.withoutRate() + " completed dut" + (s.withoutRate() == 1 ? "y has" : "ies have")
                + " no rate for its date and " + (s.withoutRate() == 1 ? "is" : "are") + " not included in the amounts");
        List<RemunerationService.FacultyPay> people = pay.faculty().stream()
                .filter(r -> department == null || department.equalsIgnoreCase(r.departmentCode())).toList();
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Faculty", List.of(Column.text("Faculty"), Column.text("Code"), Column.text("Department"),
                Column.number("Completed"), Column.number("As chief"), Column.number("Absent"), Column.number("Upcoming"),
                Column.number("Without rate"), Column.number("Amount (" + s.currency() + ")")),
                people.stream().map(r -> List.of(r.name(), r.code(), r.departmentCode(), String.valueOf(r.completed()),
                        String.valueOf(r.chiefCompleted()), String.valueOf(r.absent()), String.valueOf(r.upcoming()),
                        String.valueOf(r.withoutRate()), r.amount().toPlainString())).toList()));
        if (department == null) {
            sections.add(new Section("Departments", List.of(Column.text("Department"), Column.text("Name"), Column.number("Faculty"),
                    Column.number("Completed"), Column.number("Amount (" + s.currency() + ")")),
                    pay.departments().stream().map(d -> List.of(d.code(), d.name(), String.valueOf(d.faculty()), String.valueOf(d.completed()),
                            d.amount().toPlainString())).toList()));
            sections.add(new Section("Total", List.of(Column.text("Measure"), Column.number("Value")), List.of(
                    List.of("Completed duties", String.valueOf(s.completed())),
                    List.of("Absent", String.valueOf(s.absent())),
                    List.of("Upcoming (not yet paid)", String.valueOf(s.upcoming())),
                    List.of("Total amount (" + s.currency() + ")", s.total().toPlainString()))));
        } else {
            BigDecimal total = people.stream().map(RemunerationService.FacultyPay::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            sections.add(new Section("Total", List.of(Column.text("Measure"), Column.number("Value")), List.of(
                    List.of("Completed duties", String.valueOf(people.stream().mapToInt(RemunerationService.FacultyPay::completed).sum())),
                    List.of("Total amount (" + s.currency() + ")", total.toPlainString()))));
        }
        sections.add(new Section("Rates", List.of(Column.text("Role"), Column.text("From"), Column.number("Amount (" + s.currency() + ")")),
                remuneration.rates().stream().map(r -> List.of(r.role() == Role.CHIEF ? "Chief invigilator" : "Invigilator",
                        DAY.format(r.effectiveFrom()), r.amount().toPlainString())).toList()));
        return file(new Report("Invigilation remuneration", details, null, sections), "remuneration-" + slug(pay.periodName()), format);
    }

    // ---- shared ----

    private List<String> header(ExamPeriod period) {
        String college = colleges.current().profile().name();
        return List.of(college + " · " + period.getName() + " (" + DAY.format(period.getStartDate()) + " to " + DAY.format(period.getEndDate()) + ")",
                "Generated " + STAMP.format(collegeTime.now()) + " (" + collegeTime.zone().getId() + ")");
    }

    private ReportFile file(Report report, String name, ReportFormat format) {
        String footer = "Invigilo · " + colleges.current().profile().name() + " · " + report.title();
        return new ReportFile(name + "." + format.extension(), format, format.render(report, footer));
    }

    private static String describe(Filters f) {
        List<String> parts = new ArrayList<>();
        if (f.department() != null) parts.add("department " + f.department());
        if (f.from() != null || f.to() != null) {
            parts.add("dates " + (f.from() == null ? "start" : DAY.format(f.from())) + " to " + (f.to() == null ? "end" : DAY.format(f.to())));
        }
        if (f.session() != null) parts.add("session " + f.session());
        if (f.hall() != null) parts.add("hall " + f.hall());
        return String.join(", ", parts);
    }

    private static String time(Line l) {
        return HM.format(l.start()) + "-" + HM.format(l.end());
    }

    private static String role(Line l) {
        return l.role() == Role.CHIEF ? "Chief invigilator" : "Invigilator";
    }

    private static String name(Line l) {
        return l.open() ? "(open position)" : l.facultyName();
    }

    private static String code(Line l) {
        return l.open() ? "" : l.facultyCode();
    }

    private static String dept(Line l) {
        return l.open() ? "" : l.department();
    }

    static String slug(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "report" : slug;
    }
}
