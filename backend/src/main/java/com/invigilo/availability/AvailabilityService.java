package com.invigilo.availability;

import com.invigilo.audit.AuditService;
import com.invigilo.availability.AvailabilityRequest.Status;
import com.invigilo.college.CollegeService;
import com.invigilo.common.error.ApiException;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamSession;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyService;
import com.invigilo.identity.AppUserRepository;
import com.invigilo.identity.CollegeMembershipRepository;
import com.invigilo.identity.Role;
import com.invigilo.policy.PolicyService;
import com.invigilo.policy.PolicySettings;
import com.invigilo.tenant.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave and unavailability.
 *
 * <ul>
 *   <li>Faculty ask for themselves; the college policy decides whether full days and/or single
 *       sessions may be requested and whether HOD approval is needed (otherwise approved at once).</li>
 *   <li>The HOD of the faculty member's department, or the exam cell, approves or rejects. Nobody
 *       reviews their own request.</li>
 *   <li>The exam cell can record leave for anyone; it takes effect immediately.</li>
 *   <li>Only APPROVED requests make a faculty member unavailable for allocation.</li>
 * </ul>
 */
@Service
public class AvailabilityService {

    public record Ref(UUID id, String code, String name) {}

    public record SessionRef(UUID id, LocalDate date, String label, LocalTime startTime, LocalTime endTime) {}

    public record RequestView(UUID id, Ref faculty, String departmentCode, LocalDate fromDate, LocalDate toDate,
                              SessionRef session, AvailabilityRequest.Type type, String reason, Status status,
                              String requestedByName, String reviewedByName, Instant reviewedAt, String reviewNote,
                              Instant createdAt) {}

    public record RequestInput(LocalDate fromDate, LocalDate toDate, UUID sessionId, AvailabilityRequest.Type type,
                               String reason) {}

    public record Unavailable(Ref faculty, AvailabilityRequest.Type type, String reason, boolean wholeDay) {}

    public record SessionAvailability(SessionRef session, List<Unavailable> unavailable, List<Ref> pending) {}

    private static final String AUTO_APPROVED = "Approved automatically: the college policy needs no approval";

    private final AvailabilityRepository requests;
    private final FacultyService faculty;
    private final ExamPeriodService periods;
    private final PolicyService policies;
    private final CollegeService college;
    private final CollegeMembershipRepository memberships;
    private final AppUserRepository users;
    private final AuditService audit;
    private final Clock clock;

    public AvailabilityService(AvailabilityRepository requests, FacultyService faculty, ExamPeriodService periods,
                               PolicyService policies, CollegeService college, CollegeMembershipRepository memberships,
                               AppUserRepository users, AuditService audit, Clock clock) {
        this.requests = requests;
        this.faculty = faculty;
        this.periods = periods;
        this.policies = policies;
        this.college = college;
        this.memberships = memberships;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- faculty: own requests ----

    @Transactional(readOnly = true)
    public List<RequestView> mine(CurrentUser user) {
        return views(requests.findForFaculty(requireOwnFacultyId(user)));
    }

    @Transactional
    public RequestView requestForSelf(CurrentUser user, RequestInput input) {
        Faculty f = faculty.find(requireOwnFacultyId(user));
        PolicySettings.AvailabilityRules rules = policies.current().settings().availability();
        Resolved r = resolve(input);
        if (r.session() == null && !rules.allowFullDayUnavailability()) {
            throw ApiException.unprocessable("FULL_DAY_NOT_ALLOWED", "Your college does not allow whole-day requests. Choose a session.");
        }
        if (r.session() != null && !rules.allowSessionUnavailability()) {
            throw ApiException.unprocessable("SESSION_LEVEL_NOT_ALLOWED", "Your college only accepts whole-day requests");
        }
        if (r.from().isBefore(today())) {
            throw ApiException.unprocessable("DATE_IN_PAST", "Requests must start today or later");
        }
        Status status = rules.leaveRequiresHodApproval() ? Status.PENDING : Status.APPROVED;
        AvailabilityRequest saved = create(f, r, input, status, user);
        if (status == Status.APPROVED) saved.decide(Status.APPROVED, null, clock.instant(), AUTO_APPROVED);
        requests.flush();
        RequestView view = view(saved);
        audit.record("AVAILABILITY_REQUESTED", "faculty", f.getId(), null, view, null);
        return view;
    }

    // ---- exam cell ----

    /** Leave recorded by the exam cell is approved immediately; policy restrictions do not apply. */
    @Transactional
    public RequestView recordFor(CurrentUser admin, UUID facultyId, RequestInput input) {
        Faculty f = faculty.find(facultyId);
        AvailabilityRequest saved = create(f, resolve(input), input, Status.APPROVED, admin);
        saved.decide(Status.APPROVED, admin.userId(), clock.instant(), "Recorded by the examination cell");
        requests.flush();
        RequestView view = view(saved);
        audit.record("AVAILABILITY_RECORDED", "faculty", f.getId(), null, view, null);
        return view;
    }

    /** Exam cell: all requests. HOD: requests of their department. */
    @Transactional(readOnly = true)
    public List<RequestView> list(CurrentUser user, Status status) {
        UUID departmentId = user.hasRole(Role.EXAM_CELL_ADMIN) ? null : requireHodDepartment(user);
        return views(requests.search(status, departmentId, PageRequest.of(0, 500)));
    }

    @Transactional
    public RequestView review(CurrentUser user, UUID requestId, boolean approve, String note) {
        AvailabilityRequest r = find(requestId);
        if (!user.hasRole(Role.EXAM_CELL_ADMIN)) {
            UUID department = requireHodDepartment(user);
            if (!r.getFaculty().getDepartment().getId().equals(department)) throw notFound();
        }
        if (Objects.equals(ownFacultyId(user), r.getFaculty().getId())) {
            throw ApiException.forbidden("OWN_REQUEST", "You cannot review your own request");
        }
        if (r.getStatus() != Status.PENDING) {
            throw ApiException.conflict("ALREADY_DECIDED", "This request is already " + r.getStatus().name().toLowerCase());
        }
        RequestView before = view(r);
        r.decide(approve ? Status.APPROVED : Status.REJECTED, user.userId(), clock.instant(), note);
        requests.flush();
        RequestView after = view(r);
        audit.record(approve ? "AVAILABILITY_APPROVED" : "AVAILABILITY_REJECTED", "faculty", r.getFaculty().getId(),
                before, after, note);
        return after;
    }

    /** The faculty member withdraws a current or future request, or the exam cell cancels any. */
    @Transactional
    public RequestView cancel(CurrentUser user, UUID requestId) {
        AvailabilityRequest r = find(requestId);
        boolean admin = user.hasRole(Role.EXAM_CELL_ADMIN);
        boolean own = Objects.equals(ownFacultyId(user), r.getFaculty().getId());
        if (!admin && !own) throw notFound();
        if (!r.isActive()) {
            throw ApiException.conflict("ALREADY_DECIDED", "This request is already " + r.getStatus().name().toLowerCase());
        }
        if (!admin && r.getToDate().isBefore(today())) {
            throw ApiException.unprocessable("DATE_IN_PAST", "Past leave can only be changed by the examination cell");
        }
        RequestView before = view(r);
        r.decide(Status.CANCELLED, user.userId(), clock.instant(), null);
        requests.flush();
        RequestView after = view(r);
        audit.record("AVAILABILITY_CANCELLED", "faculty", r.getFaculty().getId(), before, after, null);
        return after;
    }

    /** Per session of a period: who is unavailable (approved) and whose requests are still pending. */
    @Transactional(readOnly = true)
    public List<SessionAvailability> periodOverview(UUID periodId) {
        ExamPeriod period = periods.findPeriod(periodId);
        List<ExamSession> sessions = periods.sessionsOf(periodId);
        List<AvailabilityRequest> active = requests.findActiveBetween(period.getStartDate(), period.getEndDate());
        return sessions.stream().map(s -> {
            List<AvailabilityRequest> covering = active.stream().filter(r -> r.covers(s)).toList();
            List<Unavailable> unavailable = covering.stream()
                    .filter(r -> r.getStatus() == Status.APPROVED)
                    .map(r -> new Unavailable(ref(r.getFaculty()), r.getType(), r.getReason(), r.getSession() == null))
                    .sorted(Comparator.comparing(u -> u.faculty().name()))
                    .toList();
            List<Ref> pending = covering.stream()
                    .filter(r -> r.getStatus() == Status.PENDING)
                    .map(r -> ref(r.getFaculty()))
                    .distinct()
                    .toList();
            return new SessionAvailability(sessionRef(s), unavailable, pending);
        }).toList();
    }

    /**
     * Faculty who must not be assigned in {@code session}: their APPROVED leave or unavailability
     * covers it. Used by allocation and by manual editing.
     */
    @Transactional(readOnly = true)
    public Set<UUID> unavailableFacultyIds(ExamSession session) {
        return requests.findActiveBetween(session.getSessionDate(), session.getSessionDate()).stream()
                .filter(r -> r.getStatus() == Status.APPROVED && r.covers(session))
                .map(r -> r.getFaculty().getId())
                .collect(Collectors.toSet());
    }

    /** Approved unavailability for many sessions at once: session id -> faculty ids. */
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> unavailabilityBySession(List<ExamSession> sessions) {
        Map<UUID, Set<UUID>> out = new HashMap<>();
        if (sessions.isEmpty()) return out;
        LocalDate from = sessions.stream().map(ExamSession::getSessionDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = sessions.stream().map(ExamSession::getSessionDate).max(LocalDate::compareTo).orElseThrow();
        List<AvailabilityRequest> approved = requests.findActiveBetween(from, to).stream()
                .filter(r -> r.getStatus() == Status.APPROVED).toList();
        for (ExamSession s : sessions) {
            Set<UUID> ids = new HashSet<>();
            for (AvailabilityRequest r : approved) if (r.covers(s)) ids.add(r.getFaculty().getId());
            out.put(s.getId(), ids);
        }
        return out;
    }

    // ---- helpers ----

    private record Resolved(LocalDate from, LocalDate to, ExamSession session) {}

    private Resolved resolve(RequestInput input) {
        if (input.sessionId() != null) {
            ExamSession s = periods.findSession(input.sessionId());
            return new Resolved(s.getSessionDate(), s.getSessionDate(), s);
        }
        if (input.fromDate() == null || input.toDate() == null) {
            throw ApiException.unprocessable("DATES_REQUIRED", "Choose the dates, or a single session");
        }
        if (input.toDate().isBefore(input.fromDate())) {
            throw ApiException.unprocessable("INVALID_DATES", "The last day is before the first day");
        }
        if (input.fromDate().plusDays(92).isBefore(input.toDate())) {
            throw ApiException.unprocessable("INVALID_DATES", "A single request can cover at most 93 days");
        }
        return new Resolved(input.fromDate(), input.toDate(), null);
    }

    private AvailabilityRequest create(Faculty f, Resolved r, RequestInput input, Status status, CurrentUser by) {
        if (!f.isActive()) throw ApiException.unprocessable("FACULTY_INACTIVE", f.getFullName() + " is inactive");
        for (AvailabilityRequest existing : requests.findActiveOverlapping(f.getId(), r.from(), r.to())) {
            boolean clash = existing.getSession() == null || r.session() == null
                    || existing.getSession().getId().equals(r.session().getId());
            if (clash) {
                throw ApiException.conflict("AVAILABILITY_OVERLAP", "There is already a "
                        + existing.getStatus().name().toLowerCase() + " request for " + existing.getFromDate()
                        + (existing.getToDate().equals(existing.getFromDate()) ? "" : " to " + existing.getToDate()));
            }
        }
        return requests.save(new AvailabilityRequest(f, r.from(), r.to(), r.session(), input.type(), input.reason(),
                status, by.userId()));
    }

    private LocalDate today() {
        ZoneId zone = ZoneId.of(college.current().profile().timeZone());
        return LocalDate.now(clock.withZone(zone));
    }

    private UUID ownFacultyId(CurrentUser user) {
        return memberships.findById(user.membershipId()).map(m -> m.getFacultyId()).orElse(null);
    }

    private UUID requireOwnFacultyId(CurrentUser user) {
        UUID id = ownFacultyId(user);
        if (id == null) {
            throw ApiException.forbidden("NOT_A_FACULTY_MEMBER", "Your login is not linked to a faculty record");
        }
        return id;
    }

    /** A HOD's department is the department of their own faculty record. */
    private UUID requireHodDepartment(CurrentUser user) {
        UUID facultyId = user.hasRole(Role.HOD) ? ownFacultyId(user) : null;
        if (facultyId == null) {
            throw ApiException.forbidden("NO_DEPARTMENT_SCOPE", "Only a head of department linked to a faculty record can review requests");
        }
        return faculty.find(facultyId).getDepartment().getId();
    }

    private AvailabilityRequest find(UUID id) {
        return requests.findById(id).orElseThrow(AvailabilityService::notFound);
    }

    private static ApiException notFound() {
        return ApiException.notFound("AVAILABILITY_NOT_FOUND", "Request not found");
    }

    private List<RequestView> views(List<AvailabilityRequest> list) {
        Set<UUID> userIds = new HashSet<>();
        list.forEach(r -> Stream.of(r.getRequestedBy(), r.getReviewedBy()).filter(Objects::nonNull).forEach(userIds::add));
        Map<UUID, String> names = names(userIds);
        return list.stream().map(r -> view(r, names)).toList();
    }

    private RequestView view(AvailabilityRequest r) {
        Set<UUID> ids = new HashSet<>();
        Stream.of(r.getRequestedBy(), r.getReviewedBy()).filter(Objects::nonNull).forEach(ids::add);
        return view(r, names(ids));
    }

    private Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) users.findAllById(ids).forEach(u -> names.put(u.getId(), u.getFullName()));
        return names;
    }

    private static RequestView view(AvailabilityRequest r, Map<UUID, String> names) {
        Faculty f = r.getFaculty();
        return new RequestView(r.getId(), ref(f), f.getDepartment().getCode(), r.getFromDate(), r.getToDate(),
                r.getSession() == null ? null : sessionRef(r.getSession()), r.getType(), r.getReason(), r.getStatus(),
                names.get(r.getRequestedBy()), names.get(r.getReviewedBy()), r.getReviewedAt(), r.getReviewNote(),
                r.getCreatedAt());
    }

    private static Ref ref(Faculty f) {
        return new Ref(f.getId(), f.getEmployeeCode(), f.getFullName());
    }

    private static SessionRef sessionRef(ExamSession s) {
        return new SessionRef(s.getId(), s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime());
    }

}
