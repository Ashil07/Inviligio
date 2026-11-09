package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.allocation.ChartEditService.HistoryEntry;
import com.invigilo.allocation.ChartEditService.Validation;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.tenant.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Duty-chart generation, review and manual editing (exam cell). Faculty and HOD views come with
 * their dashboards.
 */
@RestController
@RequestMapping("/api/v1/exam-periods/{periodId}/duty-chart")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class AllocationController {

    /** Check a change: fill an open position, or (with {@code replacesAssignmentId}) give a duty to someone else. */
    public record ValidateRequest(UUID sessionHallId, Role role, UUID facultyId, UUID replacesAssignmentId) {}

    public record AddRequest(@NotNull UUID sessionHallId, @NotNull Role role, @NotNull UUID facultyId,
                             @Size(max = 500) String reason) {}

    public record ReplaceRequest(@NotNull UUID facultyId, @NotNull Long version, @Size(max = 500) String reason) {}

    private final AllocationService allocation;
    private final ChartEditService editor;

    public AllocationController(AllocationService allocation, ChartEditService editor) {
        this.allocation = allocation;
        this.editor = editor;
    }

    @GetMapping
    public ChartView current(@PathVariable UUID periodId) {
        return allocation.current(periodId);
    }

    /** Runs allocation and replaces the period's draft chart. */
    @PostMapping("/generate")
    public ChartView generate(@PathVariable UUID periodId) {
        return allocation.generate(periodId, CurrentUser.require());
    }

    @PostMapping("/assignments/validate")
    public Validation validate(@PathVariable UUID periodId, @RequestBody ValidateRequest request) {
        return editor.validate(periodId, request.sessionHallId(), request.role(), request.facultyId(), request.replacesAssignmentId());
    }

    @PostMapping("/assignments")
    public ChartView add(@PathVariable UUID periodId, @Valid @RequestBody AddRequest request) {
        return editor.add(periodId, request.sessionHallId(), request.role(), request.facultyId(), request.reason());
    }

    @PutMapping("/assignments/{assignmentId}")
    public ChartView replace(@PathVariable UUID periodId, @PathVariable UUID assignmentId, @Valid @RequestBody ReplaceRequest request) {
        return editor.replace(periodId, assignmentId, request.facultyId(), request.version(), request.reason());
    }

    @DeleteMapping("/assignments/{assignmentId}")
    public ChartView remove(@PathVariable UUID periodId, @PathVariable UUID assignmentId, @RequestParam Long version,
                            @RequestParam(required = false) String reason) {
        return editor.remove(periodId, assignmentId, version, reason);
    }

    @GetMapping("/history")
    public List<HistoryEntry> history(@PathVariable UUID periodId) {
        return editor.history(periodId);
    }
}
