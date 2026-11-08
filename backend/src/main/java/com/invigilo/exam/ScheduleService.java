package com.invigilo.exam;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.common.persistence.Versions;
import com.invigilo.hall.Hall;
import com.invigilo.hall.HallService;
import com.invigilo.policy.PolicyService;
import com.invigilo.policy.PolicySettings;
import com.invigilo.subject.Subject;
import com.invigilo.subject.SubjectService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens in each session: exams, the halls used and how students are seated, and the
 * invigilators each hall therefore needs. Detects configurations that cannot work.
 */
@Service
public class ScheduleService {

    public record Ref(UUID id, String code, String name) {}

    public record ExamView(UUID id, Ref subject, Ref department, Exam.Type type, Integer registeredStudents,
                           int seatedStudents, String notes, long version) {}

    public record SeatView(UUID examId, String subjectCode, int studentCount) {}

    public record HallRef(UUID id, String code, String name, int capacity, int minInvigilators, boolean lab) {}

    public record SessionHallView(UUID id, HallRef hall, List<SeatView> seating, int totalStudents, Staffing staffing,
                                  Integer requiredOverride, String overrideReason, long version) {}

    public record SessionDetail(UUID id, UUID periodId, String periodName, LocalDate date, String label,
                                LocalTime startTime, LocalTime endTime, List<ExamView> exams,
                                List<SessionHallView> halls, int totalStudents, int totalPositions,
                                List<String> warnings, long version) {}

    public record SessionSummary(UUID id, LocalDate date, String label, LocalTime startTime, LocalTime endTime,
                                 int examCount, int hallCount, int students, int positions, int warningCount,
                                 long version) {}

    public record ExamInput(UUID subjectId, Exam.Type type, Integer registeredStudents, String notes) {}

    public record SeatInput(UUID examId, int studentCount) {}

    public record SessionHallInput(UUID hallId, Integer requiredOverride, String overrideReason, List<SeatInput> seating) {}

    private final ExamPeriodService periods;
    private final ExamRepository exams;
    private final SessionHallRepository sessionHalls;
    private final HallSeatingRepository seats;
    private final SubjectService subjects;
    private final HallService halls;
    private final PolicyService policies;
    private final AuditService audit;

    public ScheduleService(ExamPeriodService periods, ExamRepository exams, SessionHallRepository sessionHalls,
                           HallSeatingRepository seats, SubjectService subjects, HallService halls,
                           PolicyService policies, AuditService audit) {
        this.periods = periods;
        this.exams = exams;
        this.sessionHalls = sessionHalls;
        this.seats = seats;
        this.subjects = subjects;
        this.halls = halls;
        this.policies = policies;
        this.audit = audit;
    }

    // ---- reading ----

    @Transactional(readOnly = true)
    public SessionDetail session(UUID sessionId) {
        ExamSession session = periods.findSession(sessionId);
        return detail(session, exams.findBySessionId(sessionId), sessionHalls.findBySessionId(sessionId),
                policies.current().settings());
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> sessionSummaries(UUID periodId) {
        List<ExamSession> list = periods.sessionsOf(periodId);
        if (list.isEmpty()) return List.of();
        List<UUID> ids = list.stream().map(ExamSession::getId).toList();
        Map<UUID, List<Exam>> examsBySession = exams.findBySessionIdIn(ids).stream()
                .collect(Collectors.groupingBy(e -> e.getSession().getId()));
        Map<UUID, List<SessionHall>> hallsBySession = sessionHalls.findBySessionIdIn(ids).stream()
                .collect(Collectors.groupingBy(sh -> sh.getSession().getId()));
        PolicySettings policy = policies.current().settings();
        return list.stream().map(s -> {
            SessionDetail d = detail(s, examsBySession.getOrDefault(s.getId(), List.of()),
                    hallsBySession.getOrDefault(s.getId(), List.of()), policy);
            return new SessionSummary(s.getId(), s.getSessionDate(), s.getLabel(), s.getStartTime(), s.getEndTime(),
                    d.exams().size(), d.halls().size(), d.totalStudents(), d.totalPositions(), d.warnings().size(),
                    d.version());
        }).toList();
    }

    // ---- exams ----

    @Transactional
    public SessionDetail addExam(UUID sessionId, ExamInput input) {
        ExamSession session = editableSession(sessionId);
        Subject subject = subjects.find(input.subjectId());
        if (!subject.isActive()) {
            throw ApiException.unprocessable("SUBJECT_INACTIVE", "Subject " + subject.getCode() + " is inactive");
        }
        if (exams.existsBySessionIdAndSubjectId(sessionId, subject.getId())) {
            throw ApiException.conflict("EXAM_EXISTS", subject.getCode() + " is already examined in this session");
        }
        Exam saved = exams.saveAndFlush(new Exam(session, subject, input.type(), input.registeredStudents(), input.notes()));
        audit.record("EXAM_ADDED", "exam_session", sessionId, null, input, null);
        return session(saved.getSession().getId());
    }

    @Transactional
    public SessionDetail updateExam(UUID examId, Exam.Type type, Integer registered, String notes, Long version) {
        Exam exam = findExam(examId);
        Versions.requireUnchanged(exam, version);
        ExamPeriodService.requireEditable(exam.getSession().getPeriod());
        long seated = seats.seatedStudents(examId);
        if (registered != null && seated > registered) {
            throw ApiException.unprocessable("EXAM_OVER_SEATED", seated + " students are already seated; registered students cannot be fewer");
        }
        exam.update(type, registered, notes);
        exams.flush();
        audit.record("EXAM_UPDATED", "exam", examId, null, new ExamInput(exam.getSubject().getId(), type, registered, notes), null);
        return session(exam.getSession().getId());
    }

    @Transactional
    public SessionDetail deleteExam(UUID examId) {
        Exam exam = findExam(examId);
        ExamPeriodService.requireEditable(exam.getSession().getPeriod());
        UUID sessionId = exam.getSession().getId();
        exams.delete(exam);
        exams.flush();
        audit.record("EXAM_REMOVED", "exam_session", sessionId, exam.getSubject().getCode(), null, null);
        return session(sessionId);
    }

    // ---- halls and seating ----

    @Transactional
    public SessionDetail addHall(UUID sessionId, SessionHallInput input) {
        ExamSession session = editableSession(sessionId);
        Hall hall = halls.find(input.hallId());
        if (!hall.isActive()) throw ApiException.unprocessable("HALL_INACTIVE", "Hall " + hall.getCode() + " is inactive");
        if (sessionHalls.existsBySessionIdAndHallId(sessionId, hall.getId())) {
            throw ApiException.conflict("HALL_ALREADY_IN_SESSION", "Hall " + hall.getCode() + " is already used in this session");
        }
        requireHallFree(hall, session);
        SessionHall sh = new SessionHall(session, hall);
        apply(sh, input);
        sessionHalls.saveAndFlush(sh);
        audit.record("SESSION_HALL_ADDED", "exam_session", sessionId, null, input, null);
        return session(sessionId);
    }

    @Transactional
    public SessionDetail updateHall(UUID sessionHallId, SessionHallInput input, Long version) {
        SessionHall sh = findSessionHall(sessionHallId);
        Versions.requireUnchanged(sh, version);
        ExamPeriodService.requireEditable(sh.getSession().getPeriod());
        if (input.hallId() != null && !input.hallId().equals(sh.getHall().getId())) {
            throw ApiException.unprocessable("HALL_CHANGE_NOT_ALLOWED", "To use a different hall, remove this one and add the other");
        }
        apply(sh, input);
        sessionHalls.flush();
        audit.record("SESSION_HALL_UPDATED", "exam_session", sh.getSession().getId(), null, input, null);
        return session(sh.getSession().getId());
    }

    @Transactional
    public SessionDetail removeHall(UUID sessionHallId) {
        SessionHall sh = findSessionHall(sessionHallId);
        ExamPeriodService.requireEditable(sh.getSession().getPeriod());
        UUID sessionId = sh.getSession().getId();
        String code = sh.getHall().getCode();
        sessionHalls.delete(sh);
        sessionHalls.flush();
        audit.record("SESSION_HALL_REMOVED", "exam_session", sessionId, code, null, null);
        return session(sessionId);
    }

    private void apply(SessionHall sh, SessionHallInput input) {
        if (input.requiredOverride() != null && (input.overrideReason() == null || input.overrideReason().isBlank())) {
            throw ApiException.unprocessable("OVERRIDE_REASON_REQUIRED", "Give a reason for changing the number of invigilators");
        }
        List<SeatInput> seating = input.seating() == null ? List.of() : input.seating();
        Map<UUID, Exam> sessionExams = new HashMap<>();
        exams.findBySessionId(sh.getSession().getId()).forEach(e -> sessionExams.put(e.getId(), e));
        Map<Exam, Integer> counts = new LinkedHashMap<>();
        for (SeatInput seat : seating) {
            Exam exam = sessionExams.get(seat.examId());
            if (exam == null) {
                throw ApiException.unprocessable("EXAM_NOT_IN_SESSION", "Students can only be seated for exams of this session");
            }
            if (counts.put(exam, seat.studentCount()) != null) {
                throw ApiException.unprocessable("DUPLICATE_SEATING", exam.getSubject().getCode() + " is listed twice");
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total > sh.getHall().getCapacity()) {
            throw ApiException.unprocessable("HALL_OVER_CAPACITY", "Hall " + sh.getHall().getCode() + " seats "
                    + sh.getHall().getCapacity() + "; " + total + " students were assigned");
        }
        if (!counts.isEmpty()) {
            Map<UUID, Long> elsewhere = new HashMap<>();
            UUID self = sh.getId() == null ? new UUID(0, 0) : sh.getId();
            for (Object[] row : seats.seatedElsewhere(counts.keySet().stream().map(Exam::getId).toList(), self)) {
                elsewhere.put((UUID) row[0], (Long) row[1]);
            }
            for (var entry : counts.entrySet()) {
                Integer registered = entry.getKey().getRegisteredStudents();
                long seated = elsewhere.getOrDefault(entry.getKey().getId(), 0L) + entry.getValue();
                if (registered != null && seated > registered) {
                    throw ApiException.unprocessable("EXAM_OVER_SEATED", entry.getKey().getSubject().getCode() + " has "
                            + registered + " registered students but " + seated + " would be seated");
                }
            }
        }
        sh.setOverride(input.requiredOverride(), input.overrideReason());
        sh.replaceSeating(counts);
    }

    /** A hall can host only one session at a time (across all periods). */
    private void requireHallFree(Hall hall, ExamSession session) {
        var clash = sessionHalls.findOverlappingUses(hall.getId(), session.getId(), session.getSessionDate(),
                session.getStartTime(), session.getEndTime());
        if (!clash.isEmpty()) {
            ExamSession other = clash.getFirst().getSession();
            throw ApiException.conflict("HALL_BUSY", "Hall " + hall.getCode() + " is already used by the "
                    + other.getLabel() + " session (" + other.getStartTime() + "-" + other.getEndTime() + ") on "
                    + other.getSessionDate());
        }
    }

    // ---- assembly ----

    private SessionDetail detail(ExamSession s, List<Exam> examList, List<SessionHall> hallList, PolicySettings policy) {
        Map<UUID, Integer> seatedByExam = new HashMap<>();
        for (SessionHall sh : hallList) {
            for (HallSeating seat : sh.getSeating()) seatedByExam.merge(seat.getExam().getId(), seat.getStudentCount(), Integer::sum);
        }
        List<String> warnings = new ArrayList<>();
        List<ExamView> examViews = examList.stream()
                .sorted(Comparator.comparing(e -> e.getSubject().getCode()))
                .map(e -> {
                    int seated = seatedByExam.getOrDefault(e.getId(), 0);
                    if (seated == 0) {
                        warnings.add(e.getSubject().getCode() + " has no students seated in any hall");
                    } else if (e.getRegisteredStudents() != null && seated < e.getRegisteredStudents()) {
                        warnings.add(e.getSubject().getCode() + ": " + (e.getRegisteredStudents() - seated) + " of "
                                + e.getRegisteredStudents() + " registered students are not seated");
                    }
                    var subject = e.getSubject();
                    var dept = subject.getDepartment();
                    return new ExamView(e.getId(), new Ref(subject.getId(), subject.getCode(), subject.getName()),
                            new Ref(dept.getId(), dept.getCode(), dept.getName()), e.getType(), e.getRegisteredStudents(),
                            seated, e.getNotes(), e.getVersion());
                }).toList();
        int totalStudents = 0;
        int totalPositions = 0;
        List<SessionHallView> hallViews = new ArrayList<>();
        for (SessionHall sh : hallList.stream().sorted(Comparator.comparing(x -> x.getHall().getCode())).toList()) {
            Hall h = sh.getHall();
            boolean anyPractical = sh.getSeating().stream().anyMatch(seat -> seat.getExam().getType() == Exam.Type.PRACTICAL);
            int students = sh.totalStudents();
            Staffing staffing = Staffing.compute(policy, h.getMinInvigilators(), students, anyPractical,
                    sh.getRequiredOverride(), sh.getOverrideReason());
            if (students == 0) warnings.add("Hall " + h.getCode() + " has no students seated");
            if (anyPractical && !h.isLab()) warnings.add("A practical exam is seated in hall " + h.getCode() + ", which is not a lab");
            totalStudents += students;
            totalPositions += staffing.total();
            List<SeatView> seatViews = sh.getSeating().stream()
                    .map(seat -> new SeatView(seat.getExam().getId(), seat.getExam().getSubject().getCode(), seat.getStudentCount()))
                    .sorted(Comparator.comparing(SeatView::subjectCode))
                    .toList();
            hallViews.add(new SessionHallView(sh.getId(), new HallRef(h.getId(), h.getCode(), h.getName(), h.getCapacity(),
                    h.getMinInvigilators(), h.isLab()), seatViews, students, staffing, sh.getRequiredOverride(),
                    sh.getOverrideReason(), sh.getVersion()));
        }
        if (!examList.isEmpty() && hallList.isEmpty()) warnings.add("No halls are assigned to this session yet");
        return new SessionDetail(s.getId(), s.getPeriod().getId(), s.getPeriod().getName(), s.getSessionDate(), s.getLabel(),
                s.getStartTime(), s.getEndTime(), examViews, hallViews, totalStudents, totalPositions, warnings, s.getVersion());
    }

    private ExamSession editableSession(UUID sessionId) {
        ExamSession session = periods.findSession(sessionId);
        ExamPeriodService.requireEditable(session.getPeriod());
        return session;
    }

    private Exam findExam(UUID id) {
        return exams.findById(id).orElseThrow(() -> ApiException.notFound("EXAM_NOT_FOUND", "Exam not found"));
    }

    private SessionHall findSessionHall(UUID id) {
        return sessionHalls.findById(id).orElseThrow(() -> ApiException.notFound("SESSION_HALL_NOT_FOUND", "Session hall not found"));
    }

    // ---- delete guards for subjects and halls ----

    @Component
    static class SubjectExams implements UsageCheck<Subject> {
        private final ExamRepository exams;

        SubjectExams(ExamRepository exams) {
            this.exams = exams;
        }

        @Override
        public String usedBy() {
            return "scheduled exam(s)";
        }

        @Override
        public long countUsages(UUID subjectId) {
            return exams.countBySubjectId(subjectId);
        }
    }

    @Component
    static class HallSessions implements UsageCheck<Hall> {
        private final SessionHallRepository sessionHalls;

        HallSessions(SessionHallRepository sessionHalls) {
            this.sessionHalls = sessionHalls;
        }

        @Override
        public String usedBy() {
            return "exam session(s)";
        }

        @Override
        public long countUsages(UUID hallId) {
            return sessionHalls.countByHallId(hallId);
        }
    }
}
