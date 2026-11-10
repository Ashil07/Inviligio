package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.allocation.ChartEditService.DutySnapshot;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Versions;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamSession;
import com.invigilo.faculty.Faculty;
import com.invigilo.identity.AppUser;
import com.invigilo.identity.AppUserRepository;
import com.invigilo.tenant.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remuneration (M17). Rates per role apply from their effective date until the next rate of that role.
 * Pay is computed, never stored, from the PUBLISHED chart: a duty counts once its session has ended,
 * unless the exam cell marked the person absent (product question Q3); cancelled duties never count.
 * Attendance can be marked only after the session, so it never interferes with swaps or substitutes.
 */
@Service
public class RemunerationService {

    public record RateView(UUID id, Role role, BigDecimal amount, String currency, LocalDate effectiveFrom, String createdByName,
                           Instant createdAt, long version) {}

    public record RateInput(Role role, BigDecimal amount, String currency, LocalDate effectiveFrom) {}

    public record PeriodOption(UUID id, String name, LocalDate startDate, int publishedVersion) {}

    /** {@code withoutRate}: completed duties with no rate on their date (not in {@code amount}). */
    public record FacultyPay(UUID facultyId, String code, String name, String departmentCode, int completed, int chiefCompleted,
                             int absent, int upcoming, int withoutRate, BigDecimal amount) {}

    public record DepartmentPay(String code, String name, int faculty, int completed, BigDecimal amount) {}

    public record Summary(String currency, BigDecimal total, int completed, int absent, int upcoming, int withoutRate) {}

    /** Period fields and {@code summary} are null until something is published. */
    public record Pay(List<PeriodOption> periods, UUID periodId, String periodName, Integer chartVersion, Summary summary,
                      List<FacultyPay> faculty, List<DepartmentPay> departments) {}

    static final String RATE_ENTITY = "remuneration_rate";
    static final String DEFAULT_CURRENCY = "INR";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private final RemunerationRateRepository rates;
    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final AllocationService allocation;
    private final ExamPeriodService periods;
    private final CollegeTime collegeTime;
    private final AppUserRepository users;
    private final AuditService audit;

    RemunerationService(RemunerationRateRepository rates, DutyChartRepository charts, DutyAssignmentRepository assignments,
                        AllocationService allocation, ExamPeriodService periods, CollegeTime collegeTime, AppUserRepository users,
                        AuditService audit) {
        this.rates = rates;
        this.charts = charts;
        this.assignments = assignments;
        this.allocation = allocation;
        this.periods = periods;
        this.collegeTime = collegeTime;
        this.users = users;
        this.audit = audit;
    }

    // ---- rates ----

    @Transactional(readOnly = true)
    public List<RateView> rates() {
        List<RemunerationRate> all = rates.findAllByOrderByRoleAscEffectiveFromDesc();
        Map<UUID, String> names = users.findAllById(all.stream().map(RemunerationRate::getCreatedBy).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        return all.stream().map(r -> view(r, names)).toList();
    }

    @Transactional
    public RateView addRate(RateInput input, CurrentUser user) {
        if (input.role() == null) throw ApiException.unprocessable("ROLE_REQUIRED", "Choose chief or invigilator");
        if (input.effectiveFrom() == null) throw ApiException.unprocessable("EFFECTIVE_FROM_REQUIRED", "Say from which date the rate applies");
        BigDecimal amount = input.amount();
        if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Enter an amount from 0 to 1,000,000 with at most two decimals");
        }
        List<RemunerationRate> existing = rates.findAllByOrderByRoleAscEffectiveFromDesc();
        String collegeCurrency = existing.stream().map(RemunerationRate::getCurrency).findFirst().orElse(null);
        String currency = input.currency() == null || input.currency().isBlank()
                ? Optional.ofNullable(collegeCurrency).orElse(DEFAULT_CURRENCY)
                : input.currency().strip().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw ApiException.unprocessable("INVALID_CURRENCY", "Use a three-letter currency code such as INR");
        if (collegeCurrency != null && !collegeCurrency.equals(currency)) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "All rates use " + collegeCurrency + "; amounts in different currencies cannot be added up");
        }
        if (rates.existsByRoleAndEffectiveFrom(input.role(), input.effectiveFrom())) {
            throw ApiException.conflict("RATE_EXISTS", "There is already a " + roleName(input.role()) + " rate from that date; delete it first");
        }
        RemunerationRate rate = rates.saveAndFlush(new RemunerationRate(input.role(), amount.setScale(2), currency, input.effectiveFrom(),
                user.userId()));
        Map<UUID, String> me = users.findById(user.userId()).map(u -> Map.of(u.getId(), u.getFullName())).orElse(Map.of());
        RateView view = view(rate, me);
        audit.record("REMUNERATION_RATE_ADDED", RATE_ENTITY, rate.getId(), null, view, null);
        return view;
    }

    @Transactional
    public void deleteRate(UUID id, Long version) {
        RemunerationRate rate = rates.findById(id).orElseThrow(() -> ApiException.notFound("RATE_NOT_FOUND", "Rate not found"));
        Versions.requireUnchanged(rate, version);
        RateView before = view(rate, Map.of());
        rates.delete(rate);
        rates.flush();
        audit.record("REMUNERATION_RATE_DELETED", RATE_ENTITY, id, before, null, null);
    }

    // ---- pay ----

    /** {@code periodId} null picks the published period that starts last. */
    @Transactional(readOnly = true)
    public Pay pay(UUID periodId) {
        Map<UUID, DutyChart> published = charts.findByStatus(DutyChart.Status.PUBLISHED).stream()
                .collect(Collectors.toMap(DutyChart::getPeriodId, c -> c, (a, b) -> a));
        List<ExamPeriodService.PeriodView> withChart = periods.list().stream().filter(p -> published.containsKey(p.id())).toList();
        List<PeriodOption> options = withChart.stream()
                .map(p -> new PeriodOption(p.id(), p.name(), p.startDate(), published.get(p.id()).getChartVersion())).toList();
        Optional<ExamPeriodService.PeriodView> selected = periodId == null ? withChart.stream().findFirst()
                : withChart.stream().filter(p -> p.id().equals(periodId)).findFirst();
        if (periodId != null && selected.isEmpty()) {
            throw ApiException.notFound("PUBLISHED_CHART_NOT_FOUND", "No published duty chart for this exam period");
        }
        if (selected.isEmpty()) return new Pay(options, null, null, null, null, List.of(), List.of());

        ExamPeriodService.PeriodView period = selected.get();
        DutyChart chart = published.get(period.id());
        Map<Role, List<RemunerationRate>> byRole = new EnumMap<>(Role.class);
        for (RemunerationRate r : rates.findAllByOrderByRoleAscEffectiveFromDesc()) byRole.computeIfAbsent(r.getRole(), k -> new ArrayList<>()).add(r);
        String currency = byRole.values().stream().flatMap(List::stream).map(RemunerationRate::getCurrency).findFirst().orElse(DEFAULT_CURRENCY);

        Map<UUID, Faculty> people = new LinkedHashMap<>();
        Map<UUID, int[]> counts = new HashMap<>(); // completed, chief completed, absent, upcoming, without rate
        Map<UUID, BigDecimal> amounts = new HashMap<>();
        for (DutyAssignment d : assignments.findForChart(chart.getId())) {
            if (d.getStatus() == DutyAssignment.Status.CANCELLED) continue;
            Faculty f = d.getFaculty();
            people.putIfAbsent(f.getId(), f);
            int[] c = counts.computeIfAbsent(f.getId(), k -> new int[5]);
            if (d.getStatus() == DutyAssignment.Status.ABSENT) {
                c[2]++;
            } else if (!ended(d)) {
                c[3]++;
            } else {
                c[0]++;
                if (d.getRole() == Role.CHIEF) c[1]++;
                BigDecimal rate = rateOn(byRole.getOrDefault(d.getRole(), List.of()), d.getSessionHall().getSession().getSessionDate());
                if (rate == null) c[4]++;
                else amounts.merge(f.getId(), rate, BigDecimal::add);
            }
        }
        List<FacultyPay> rows = people.values().stream()
                .map(f -> {
                    int[] c = counts.get(f.getId());
                    return new FacultyPay(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getDepartment().getCode(), c[0], c[1], c[2], c[3],
                            c[4], amounts.getOrDefault(f.getId(), BigDecimal.ZERO.setScale(2)));
                })
                .sorted(Comparator.comparing(FacultyPay::amount).reversed().thenComparing(FacultyPay::name))
                .toList();
        Map<String, String> deptNames = new HashMap<>();
        people.values().forEach(f -> deptNames.put(f.getDepartment().getCode(), f.getDepartment().getName()));
        List<DepartmentPay> departments = rows.stream()
                .collect(Collectors.groupingBy(FacultyPay::departmentCode, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new DepartmentPay(e.getKey(), deptNames.get(e.getKey()), e.getValue().size(),
                        e.getValue().stream().mapToInt(FacultyPay::completed).sum(),
                        e.getValue().stream().map(FacultyPay::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add)))
                .sorted(Comparator.comparing(DepartmentPay::amount).reversed().thenComparing(DepartmentPay::code))
                .toList();
        Summary summary = new Summary(currency, rows.stream().map(FacultyPay::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
                rows.stream().mapToInt(FacultyPay::completed).sum(), rows.stream().mapToInt(FacultyPay::absent).sum(),
                rows.stream().mapToInt(FacultyPay::upcoming).sum(), rows.stream().mapToInt(FacultyPay::withoutRate).sum());
        return new Pay(options, period.id(), period.name(), chart.getChartVersion(), summary, rows, departments);
    }

    /** The newest rate that had started by {@code date} ({@code list} is newest first), or null. */
    static BigDecimal rateOn(List<RemunerationRate> list, LocalDate date) {
        return list.stream().filter(r -> !r.getEffectiveFrom().isAfter(date)).findFirst().map(RemunerationRate::getAmount).orElse(null);
    }

    // ---- attendance ----

    /**
     * Marks a duty of the published chart absent (not paid) or present again. Only after the session
     * has ended; {@code version} is the duty's version on screen.
     */
    @Transactional
    public ChartView markAttendance(UUID periodId, UUID assignmentId, boolean absent, Long version, String reason) {
        ExamPeriod period = periods.findPeriod(periodId);
        if (!period.allowsChartChanges()) {
            throw ApiException.conflict("PERIOD_LOCKED", "This examination period is closed; its duties can no longer change");
        }
        DutyChart chart = charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.PUBLISHED)
                .orElseThrow(() -> ApiException.conflict("NO_PUBLISHED_CHART", "Attendance is marked on the published duty chart"));
        DutyAssignment duty = assignments.findById(assignmentId).filter(d -> d.getChartId().equals(chart.getId()))
                .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND", "That duty is not in the published duty chart"));
        Versions.requireUnchanged(duty, version);
        if (duty.getStatus() == DutyAssignment.Status.CANCELLED) {
            throw ApiException.conflict("DUTY_CANCELLED", "This duty was cancelled");
        }
        if (!ended(duty)) throw ApiException.conflict("SESSION_NOT_OVER", "Mark attendance once the session has ended");
        boolean wasAbsent = duty.getStatus() == DutyAssignment.Status.ABSENT;
        if (wasAbsent == absent) {
            throw ApiException.conflict("ATTENDANCE_UNCHANGED", duty.getFaculty().getFullName() + " is already marked " + (absent ? "absent" : "present"));
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why != null && why.length() > 500) throw ApiException.unprocessable("REASON_TOO_LONG", "Keep the reason under 500 characters");
        DutySnapshot before = DutySnapshot.of(duty);
        duty.markAbsent(absent);
        assignments.flush();
        audit.record(absent ? "DUTY_MARKED_ABSENT" : "DUTY_MARKED_PRESENT", ChartEditService.ENTITY, chart.getId(), before,
                DutySnapshot.of(duty), why);
        return allocation.view(chart, period);
    }

    // ---- helpers ----

    private boolean ended(DutyAssignment d) {
        ExamSession s = d.getSessionHall().getSession();
        return !s.getSessionDate().atTime(s.getEndTime()).isAfter(collegeTime.now());
    }

    private static RateView view(RemunerationRate r, Map<UUID, String> names) {
        return new RateView(r.getId(), r.getRole(), r.getAmount(), r.getCurrency(), r.getEffectiveFrom(),
                r.getCreatedBy() == null ? null : names.get(r.getCreatedBy()), r.getCreatedAt(), r.getVersion());
    }

    static String roleName(Role role) {
        return role == Role.CHIEF ? "chief invigilator" : "invigilator";
    }
}
