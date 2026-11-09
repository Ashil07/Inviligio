package com.invigilo.exam;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Versions;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Examination periods and their sessions. */
@Service
public class ExamPeriodService {

    public record PeriodView(UUID id, String name, LocalDate startDate, LocalDate endDate, ExamPeriod.Status status,
                             long sessionCount, long version) {}

    public record SessionInput(LocalDate date, String label, LocalTime startTime, LocalTime endTime) {}

    public record Slot(String label, LocalTime startTime, LocalTime endTime) {}

    public record BulkResult(int created, int skipped) {}

    private final ExamPeriodRepository periods;
    private final ExamSessionRepository sessions;
    private final SessionHallRepository sessionHalls;
    private final AuditService audit;

    public ExamPeriodService(ExamPeriodRepository periods, ExamSessionRepository sessions,
                             SessionHallRepository sessionHalls, AuditService audit) {
        this.periods = periods;
        this.sessions = sessions;
        this.sessionHalls = sessionHalls;
        this.audit = audit;
    }

    // ---- periods ----

    @Transactional(readOnly = true)
    public List<PeriodView> list() {
        return periods.findAllByOrderByStartDateDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PeriodView get(UUID id) {
        return view(findPeriod(id));
    }

    @Transactional
    public PeriodView create(String name, LocalDate start, LocalDate end) {
        checkDates(start, end);
        if (periods.existsByName(name.trim())) throw nameTaken(name);
        ExamPeriod saved = periods.saveAndFlush(new ExamPeriod(name, start, end));
        PeriodView view = view(saved);
        audit.record("EXAM_PERIOD_CREATED", "exam_period", saved.getId(), null, view, null);
        return view;
    }

    @Transactional
    public PeriodView update(UUID id, String name, LocalDate start, LocalDate end, Long version) {
        ExamPeriod period = findPeriod(id);
        Versions.requireUnchanged(period, version);
        requireEditable(period);
        checkDates(start, end);
        if (periods.existsByNameAndIdNot(name.trim(), id)) throw nameTaken(name);
        long outside = sessions.countOutside(id, start, end);
        if (outside > 0) {
            throw ApiException.unprocessable("PERIOD_EXCLUDES_SESSIONS",
                    outside + " session(s) fall outside the new dates. Move or delete them first.");
        }
        PeriodView before = view(period);
        period.update(name, start, end);
        periods.flush();
        PeriodView after = view(period);
        audit.record("EXAM_PERIOD_UPDATED", "exam_period", id, before, after, null);
        return after;
    }

    /** Deletes the period with all its sessions, exams and seating (only while still editable). */
    @Transactional
    public void delete(UUID id) {
        ExamPeriod period = findPeriod(id);
        requireEditable(period);
        PeriodView before = view(period);
        periods.delete(period);
        audit.record("EXAM_PERIOD_DELETED", "exam_period", id, before, null, null);
    }

    // ---- sessions ----

    @Transactional
    public ExamSession createSession(UUID periodId, SessionInput input) {
        ExamPeriod period = findPeriod(periodId);
        requireEditable(period);
        checkSession(period, input, null);
        ExamSession saved = sessions.saveAndFlush(
                new ExamSession(period, input.date(), input.label(), input.startTime(), input.endTime()));
        audit.record("EXAM_SESSION_CREATED", "exam_session", saved.getId(), null, input, null);
        return saved;
    }

    /**
     * Creates one session per slot for every date in the range, except excluded weekdays and
     * date/label combinations that already exist.
     */
    @Transactional
    public BulkResult createSessions(UUID periodId, LocalDate from, LocalDate to, Set<DayOfWeek> skipDays, List<Slot> slots) {
        ExamPeriod period = findPeriod(periodId);
        requireEditable(period);
        if (to.isBefore(from)) throw ApiException.unprocessable("INVALID_DATES", "The end date is before the start date");
        if (!period.contains(from) || !period.contains(to)) {
            throw ApiException.unprocessable("SESSION_OUTSIDE_PERIOD", "Dates must be within the examination period");
        }
        int created = 0;
        int skipped = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (skipDays.contains(d.getDayOfWeek())) continue;
            for (Slot slot : slots) {
                String label = ExamSession.normalizeLabel(slot.label());
                if (sessions.existsByPeriodIdAndSessionDateAndLabel(periodId, d, label)) {
                    skipped++;
                    continue;
                }
                checkTimes(slot.startTime(), slot.endTime());
                sessions.save(new ExamSession(period, d, label, slot.startTime(), slot.endTime()));
                created++;
            }
        }
        sessions.flush();
        audit.record("EXAM_SESSIONS_GENERATED", "exam_period", periodId, null,
                new BulkResult(created, skipped), null);
        return new BulkResult(created, skipped);
    }

    @Transactional
    public ExamSession updateSession(UUID sessionId, SessionInput input, Long version) {
        ExamSession session = findSession(sessionId);
        Versions.requireUnchanged(session, version);
        requireEditable(session.getPeriod());
        checkSession(session.getPeriod(), input, sessionId);
        // Halls already used here must stay free at the new time.
        for (SessionHall sh : sessionHalls.findBySessionId(sessionId)) {
            if (!sessionHalls.findOverlappingUses(sh.getHall().getId(), sessionId, input.date(), input.startTime(),
                    input.endTime()).isEmpty()) {
                throw ApiException.conflict("HALL_BUSY", "Hall " + sh.getHall().getCode()
                        + " is used by another session at the new time");
            }
        }
        SessionInput before = new SessionInput(session.getSessionDate(), session.getLabel(), session.getStartTime(),
                session.getEndTime());
        session.update(input.date(), input.label(), input.startTime(), input.endTime());
        sessions.flush();
        audit.record("EXAM_SESSION_UPDATED", "exam_session", sessionId, before, input, null);
        return session;
    }

    @Transactional
    public void deleteSession(UUID sessionId) {
        ExamSession session = findSession(sessionId);
        requireEditable(session.getPeriod());
        SessionInput before = new SessionInput(session.getSessionDate(), session.getLabel(), session.getStartTime(),
                session.getEndTime());
        sessions.delete(session);
        audit.record("EXAM_SESSION_DELETED", "exam_session", sessionId, before, null, null);
    }

    @Transactional(readOnly = true)
    public List<ExamSession> sessionsOf(UUID periodId) {
        findPeriod(periodId);
        return sessions.findByPeriodIdOrderBySessionDateAscStartTimeAsc(periodId);
    }

    @Transactional
    public void markAllocating(UUID periodId) {
        findPeriod(periodId).startAllocating();
    }

    // ---- lookups and rules ----

    @Transactional(readOnly = true)
    public ExamPeriod findPeriod(UUID id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("EXAM_PERIOD_NOT_FOUND", "Examination period not found"));
    }

    @Transactional(readOnly = true)
    public ExamSession findSession(UUID id) {
        return sessions.findById(id).orElseThrow(() -> ApiException.notFound("EXAM_SESSION_NOT_FOUND", "Session not found"));
    }

    static void requireEditable(ExamPeriod period) {
        if (!period.isEditable()) {
            throw ApiException.conflict("PERIOD_LOCKED", "This examination period is " + period.getStatus()
                    + " and can no longer be changed here");
        }
    }

    private void checkSession(ExamPeriod period, SessionInput input, UUID existingId) {
        if (!period.contains(input.date())) {
            throw ApiException.unprocessable("SESSION_OUTSIDE_PERIOD", "The date must be between "
                    + period.getStartDate() + " and " + period.getEndDate());
        }
        checkTimes(input.startTime(), input.endTime());
        String label = ExamSession.normalizeLabel(input.label());
        boolean exists = existingId == null
                ? sessions.existsByPeriodIdAndSessionDateAndLabel(period.getId(), input.date(), label)
                : sessions.existsByPeriodIdAndSessionDateAndLabelAndIdNot(period.getId(), input.date(), label, existingId);
        if (exists) {
            throw ApiException.conflict("SESSION_EXISTS", "There is already a " + label + " session on " + input.date());
        }
    }

    private static void checkTimes(LocalTime start, LocalTime end) {
        if (!end.isAfter(start)) {
            throw ApiException.unprocessable("INVALID_TIMES", "The end time must be after the start time");
        }
    }

    private static void checkDates(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) throw ApiException.unprocessable("INVALID_DATES", "The end date is before the start date");
        if (start.plusDays(366).isBefore(end)) throw ApiException.unprocessable("INVALID_DATES", "A period can span at most a year");
    }

    private PeriodView view(ExamPeriod p) {
        return new PeriodView(p.getId(), p.getName(), p.getStartDate(), p.getEndDate(), p.getStatus(),
                p.getId() == null ? 0 : sessions.countByPeriodId(p.getId()), p.getVersion() == null ? 0 : p.getVersion());
    }

    private static ApiException nameTaken(String name) {
        return ApiException.conflict("EXAM_PERIOD_NAME_TAKEN", "An examination period named '" + name.trim() + "' already exists");
    }
}
