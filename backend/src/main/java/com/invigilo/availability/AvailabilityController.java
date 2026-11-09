package com.invigilo.availability;

import com.invigilo.availability.AvailabilityService.RequestInput;
import com.invigilo.availability.AvailabilityService.RequestView;
import com.invigilo.availability.AvailabilityService.SessionAvailability;
import com.invigilo.tenant.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AvailabilityController {

    /** Either a date range (whole days) or a single {@code sessionId}. */
    public record AvailabilityRequestBody(LocalDate fromDate, LocalDate toDate, UUID sessionId,
                                          @NotNull AvailabilityRequest.Type type, @Size(max = 500) String reason) {
        RequestInput input() {
            return new RequestInput(fromDate, toDate, sessionId, type, reason);
        }
    }

    public record RecordRequestBody(@NotNull UUID facultyId, LocalDate fromDate, LocalDate toDate, UUID sessionId,
                                    @NotNull AvailabilityRequest.Type type, @Size(max = 500) String reason) {}

    public record ReviewBody(@Size(max = 500) String note) {}

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    // ---- faculty ----

    @GetMapping("/me/availability")
    public List<RequestView> mine() {
        return availability.mine(CurrentUser.require());
    }

    @PostMapping("/me/availability")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView request(@Valid @RequestBody AvailabilityRequestBody body) {
        return availability.requestForSelf(CurrentUser.require(), body.input());
    }

    /** The service checks that the caller owns the request or is the exam cell. */
    @PostMapping("/availability/{id}/cancel")
    public RequestView cancel(@PathVariable UUID id) {
        return availability.cancel(CurrentUser.require(), id);
    }

    // ---- reviewers ----

    @GetMapping("/availability")
    @PreAuthorize("hasAnyRole('EXAM_CELL_ADMIN', 'HOD')")
    public List<RequestView> list(@RequestParam(required = false) AvailabilityRequest.Status status) {
        return availability.list(CurrentUser.require(), status);
    }

    @PostMapping("/availability/{id}/approve")
    @PreAuthorize("hasAnyRole('EXAM_CELL_ADMIN', 'HOD')")
    public RequestView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ReviewBody body) {
        return availability.review(CurrentUser.require(), id, true, body == null ? null : body.note());
    }

    @PostMapping("/availability/{id}/reject")
    @PreAuthorize("hasAnyRole('EXAM_CELL_ADMIN', 'HOD')")
    public RequestView reject(@PathVariable UUID id, @Valid @RequestBody(required = false) ReviewBody body) {
        return availability.review(CurrentUser.require(), id, false, body == null ? null : body.note());
    }

    // ---- exam cell ----

    @PostMapping("/availability")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public RequestView record(@Valid @RequestBody RecordRequestBody body) {
        return availability.recordFor(CurrentUser.require(), body.facultyId(),
                new RequestInput(body.fromDate(), body.toDate(), body.sessionId(), body.type(), body.reason()));
    }

    @GetMapping("/exam-periods/{periodId}/availability")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public List<SessionAvailability> periodOverview(@PathVariable UUID periodId) {
        return availability.periodOverview(periodId);
    }
}
