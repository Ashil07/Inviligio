package com.invigilo.exam;

import com.invigilo.exam.ExamPeriodService.BulkResult;
import com.invigilo.exam.ExamPeriodService.PeriodView;
import com.invigilo.exam.ExamPeriodService.SessionInput;
import com.invigilo.exam.ExamPeriodService.Slot;
import com.invigilo.exam.ScheduleService.ExamInput;
import com.invigilo.exam.ScheduleService.SeatInput;
import com.invigilo.exam.ScheduleService.SessionDetail;
import com.invigilo.exam.ScheduleService.SessionHallInput;
import com.invigilo.exam.ScheduleService.SessionSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Examination timetable. Every member may read it; only the exam cell changes it. */
@RestController
@RequestMapping("/api/v1")
public class ScheduleController {

    public record PeriodRequest(@NotBlank @Size(max = 200) String name, @NotNull LocalDate startDate,
                                @NotNull LocalDate endDate, Long version) {}

    public record SessionRequest(@NotNull LocalDate date, @NotBlank @Size(max = 20) String label,
                                 @NotNull LocalTime startTime, @NotNull LocalTime endTime, Long version) {
        SessionInput input() {
            return new SessionInput(date, label, startTime, endTime);
        }
    }

    public record SlotRequest(@NotBlank @Size(max = 20) String label, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}

    public record BulkSessionsRequest(@NotNull LocalDate fromDate, @NotNull LocalDate toDate, Set<DayOfWeek> skipDays,
                                      @NotEmpty @Size(max = 4) List<@Valid SlotRequest> slots) {}

    public record ExamRequest(@NotNull UUID subjectId, @NotNull Exam.Type type,
                              @Min(0) @Max(10000) Integer registeredStudents, @Size(max = 500) String notes, Long version) {}

    public record SeatRequest(@NotNull UUID examId, @NotNull @Min(1) @Max(2000) Integer studentCount) {}

    public record SessionHallRequest(UUID hallId, @Min(0) @Max(20) Integer requiredOverride,
                                     @Size(max = 300) String overrideReason,
                                     @Size(max = 50) List<@Valid SeatRequest> seating, Long version) {
        SessionHallInput input() {
            return new SessionHallInput(hallId, requiredOverride, overrideReason,
                    seating == null ? List.of() : seating.stream().map(s -> new SeatInput(s.examId(), s.studentCount())).toList());
        }
    }

    private final ExamPeriodService periods;
    private final ScheduleService schedule;

    public ScheduleController(ExamPeriodService periods, ScheduleService schedule) {
        this.periods = periods;
        this.schedule = schedule;
    }

    // ---- periods ----

    @GetMapping("/exam-periods")
    public List<PeriodView> periods() {
        return periods.list();
    }

    @GetMapping("/exam-periods/{id}")
    public PeriodView period(@PathVariable UUID id) {
        return periods.get(id);
    }

    @PostMapping("/exam-periods")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public PeriodView createPeriod(@Valid @RequestBody PeriodRequest r) {
        return periods.create(r.name(), r.startDate(), r.endDate());
    }

    @PutMapping("/exam-periods/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public PeriodView updatePeriod(@PathVariable UUID id, @Valid @RequestBody PeriodRequest r) {
        return periods.update(id, r.name(), r.startDate(), r.endDate(), r.version());
    }

    @DeleteMapping("/exam-periods/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public void deletePeriod(@PathVariable UUID id) {
        periods.delete(id);
    }

    // ---- sessions ----

    @GetMapping("/exam-periods/{id}/sessions")
    public List<SessionSummary> sessions(@PathVariable UUID id) {
        return schedule.sessionSummaries(id);
    }

    @PostMapping("/exam-periods/{id}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail createSession(@PathVariable UUID id, @Valid @RequestBody SessionRequest r) {
        return schedule.session(periods.createSession(id, r.input()).getId());
    }

    @PostMapping("/exam-periods/{id}/sessions/bulk")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public BulkResult createSessions(@PathVariable UUID id, @Valid @RequestBody BulkSessionsRequest r) {
        return periods.createSessions(id, r.fromDate(), r.toDate(), r.skipDays() == null ? Set.of() : r.skipDays(),
                r.slots().stream().map(s -> new Slot(s.label(), s.startTime(), s.endTime())).toList());
    }

    @GetMapping("/exam-sessions/{id}")
    public SessionDetail session(@PathVariable UUID id) {
        return schedule.session(id);
    }

    @PutMapping("/exam-sessions/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail updateSession(@PathVariable UUID id, @Valid @RequestBody SessionRequest r) {
        periods.updateSession(id, r.input(), r.version());
        return schedule.session(id);
    }

    @DeleteMapping("/exam-sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public void deleteSession(@PathVariable UUID id) {
        periods.deleteSession(id);
    }

    // ---- exams ----

    @PostMapping("/exam-sessions/{id}/exams")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail addExam(@PathVariable UUID id, @Valid @RequestBody ExamRequest r) {
        return schedule.addExam(id, new ExamInput(r.subjectId(), r.type(), r.registeredStudents(), r.notes()));
    }

    @PutMapping("/exams/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail updateExam(@PathVariable UUID id, @Valid @RequestBody ExamRequest r) {
        return schedule.updateExam(id, r.type(), r.registeredStudents(), r.notes(), r.version());
    }

    @DeleteMapping("/exams/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail deleteExam(@PathVariable UUID id) {
        return schedule.deleteExam(id);
    }

    // ---- halls ----

    @PostMapping("/exam-sessions/{id}/halls")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail addHall(@PathVariable UUID id, @Valid @RequestBody SessionHallRequest r) {
        if (r.hallId() == null) {
            throw com.invigilo.common.error.ApiException.unprocessable("HALL_REQUIRED", "Choose a hall");
        }
        return schedule.addHall(id, r.input());
    }

    @PutMapping("/session-halls/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail updateHall(@PathVariable UUID id, @Valid @RequestBody SessionHallRequest r) {
        return schedule.updateHall(id, r.input(), r.version());
    }

    @DeleteMapping("/session-halls/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SessionDetail removeHall(@PathVariable UUID id) {
        return schedule.removeHall(id);
    }
}
