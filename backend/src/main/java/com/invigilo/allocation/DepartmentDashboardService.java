package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.ChartChecker;
import com.invigilo.allocation.engine.ChartChecker.Load;
import com.invigilo.common.error.ApiException;
import com.invigilo.department.Department;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamPeriodService.PeriodView;
import com.invigilo.exam.ExamSession;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyService;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.identity.CollegeMembershipRepository;
import com.invigilo.tenant.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * The HOD dashboard (M12): the head's own department in one published chart. Who is on duty when,
 * and how each person's load compares with an even share of the whole college's duties, using the
 * same rule as the chart editor's workload warning ({@link ChartChecker#load}). Drafts are never
 * shown, as for faculty. Leave approvals stay with the availability endpoints.
 */
@Service
public class DepartmentDashboardService {

    public record DepartmentRef(UUID id, String code, String name) {}

    /** An exam period that has a published chart. */
    public record PeriodOption(UUID id, String name, LocalDate startDate, LocalDate endDate, int publishedVersion) {}

    /**
     * One person of the department. {@code fairShare} is the even share for their duty weight;
     * {@code load} is null for inactive faculty, who are not expected to take duties.
     */
    public record MemberRow(UUID facultyId, String code, String name, String designation, boolean active, BigDecimal dutyWeight,
                            int duties, int chiefDuties, double fairShare, Load load) {}

    public record DepartmentDuty(UUID assignmentId, LocalDate date, String sessionLabel, LocalTime startTime, LocalTime endTime,
                                 String hallCode, String hallName, UUID facultyId, String facultyCode, String facultyName, Role role) {}

    /**
     * {@code collegeAverage}: duties per active faculty member in the whole college;
     * {@code departmentAverage}: the same within the department. Period fields are null when no
     * chart has been published yet.
     */
    public record Overview(DepartmentRef department, List<PeriodOption> periods, UUID periodId, String periodName,
                           Integer chartVersion, Instant publishedAt, int departmentDuties, double collegeAverage,
                           double departmentAverage, List<MemberRow> faculty, List<DepartmentDuty> duties) {}

    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final ExamPeriodService periods;
    private final FacultyService faculty;
    private final CollegeMembershipRepository memberships;

    public DepartmentDashboardService(DutyChartRepository charts, DutyAssignmentRepository assignments, ExamPeriodService periods,
                                      FacultyService faculty, CollegeMembershipRepository memberships) {
        this.charts = charts;
        this.assignments = assignments;
        this.periods = periods;
        this.faculty = faculty;
        this.memberships = memberships;
    }

    /** {@code periodId} null picks the published period that starts last. */
    @Transactional(readOnly = true)
    public Overview overview(CurrentUser user, UUID periodId) {
        Department department = requireHodDepartment(user);
        DepartmentRef ref = new DepartmentRef(department.getId(), department.getCode(), department.getName());
        List<Faculty> people = faculty.inDepartment(department.getId());

        Map<UUID, DutyChart> published = charts.findByStatus(DutyChart.Status.PUBLISHED).stream()
                .collect(Collectors.toMap(DutyChart::getPeriodId, c -> c, (a, b) -> a));
        List<PeriodOption> options = periods.list().stream() // newest first
                .filter(p -> published.containsKey(p.id()))
                .map(p -> new PeriodOption(p.id(), p.name(), p.startDate(), p.endDate(), published.get(p.id()).getChartVersion()))
                .toList();

        Optional<PeriodOption> selected = periodId == null ? options.stream().findFirst()
                : options.stream().filter(p -> p.id().equals(periodId)).findFirst();
        if (periodId != null && selected.isEmpty()) {
            // Unknown, another college's, or not published: all look the same to a HOD.
            throw ApiException.notFound("PUBLISHED_CHART_NOT_FOUND", "No published duty chart for this exam period");
        }
        if (selected.isEmpty()) {
            List<MemberRow> rows = people.stream().map(f -> row(f, 0, 0, 0)).toList();
            return new Overview(ref, options, null, null, null, null, 0, 0, 0, rows, List.of());
        }

        PeriodOption period = selected.get();
        DutyChart chart = published.get(period.id());
        List<DutyAssignment> all = assignments.findForChart(chart.getId()).stream()
                .filter(a -> a.getStatus() != DutyAssignment.Status.CANCELLED)
                .toList();
        long activeFaculty = faculty.activeCount();
        double collegeAverage = activeFaculty == 0 ? 0 : all.size() / (double) activeFaculty;

        List<DutyAssignment> ours = all.stream()
                .filter(a -> a.getFaculty().getDepartment().getId().equals(department.getId()))
                .toList();
        Map<UUID, int[]> counts = new HashMap<>();
        for (DutyAssignment a : ours) {
            int[] c = counts.computeIfAbsent(a.getFaculty().getId(), k -> new int[2]);
            c[0]++;
            if (a.getRole() == Role.CHIEF) c[1]++;
        }
        Map<UUID, Faculty> members = new LinkedHashMap<>();
        people.forEach(f -> members.put(f.getId(), f));
        List<MemberRow> rows = members.values().stream()
                .map(f -> {
                    int[] c = counts.getOrDefault(f.getId(), new int[2]);
                    return row(f, c[0], c[1], collegeAverage);
                })
                .sorted(Comparator.comparingInt(MemberRow::duties).reversed().thenComparing(MemberRow::name))
                .toList();
        long activeMembers = people.stream().filter(Faculty::isActive).count();
        double departmentAverage = activeMembers == 0 ? 0 : ours.size() / (double) activeMembers;

        List<DepartmentDuty> duties = ours.stream()
                .map(DepartmentDashboardService::duty)
                .sorted(Comparator.comparing(DepartmentDuty::date).thenComparing(DepartmentDuty::startTime)
                        .thenComparing(DepartmentDuty::hallCode).thenComparing(DepartmentDuty::facultyName))
                .toList();
        return new Overview(ref, options, period.id(), period.name(), chart.getChartVersion(), chart.getPublishedAt(), ours.size(),
                round1(collegeAverage), round1(departmentAverage), rows, duties);
    }

    private static MemberRow row(Faculty f, int duties, int chief, double collegeAverage) {
        double weight = f.getDutyWeight().doubleValue();
        Load load = f.isActive() ? ChartChecker.load(duties, collegeAverage, weight) : null;
        return new MemberRow(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDesignation(), f.isActive(), f.getDutyWeight(),
                duties, chief, f.isActive() ? round1(collegeAverage * weight) : 0, load);
    }

    private static DepartmentDuty duty(DutyAssignment a) {
        ExamSession s = a.getSessionHall().getSession();
        Faculty f = a.getFaculty();
        return new DepartmentDuty(a.getId(), s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime(),
                a.getSessionHall().getHall().getCode(), a.getSessionHall().getHall().getName(), f.getId(), f.getEmployeeCode(),
                f.getFullName(), a.getRole());
    }

    /** A HOD's department is the department of their own faculty record. */
    private Department requireHodDepartment(CurrentUser user) {
        UUID facultyId = memberships.findById(user.membershipId()).map(CollegeMembership::getFacultyId).orElse(null);
        if (facultyId == null) {
            throw ApiException.forbidden("NO_DEPARTMENT_SCOPE", "Only a head of department linked to a faculty record has a department");
        }
        return faculty.find(facultyId).getDepartment();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
