package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.allocation.SubstituteService.Options;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Emergency substitutes for duties of the published chart (M14). Exam cell only. */
@RestController
@RequestMapping("/api/v1/exam-periods/{periodId}/duty-chart/assignments/{assignmentId}/substitute")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class SubstituteController {

    /** {@code version}: the duty's version when the candidates were loaded. */
    public record SubstituteRequest(UUID facultyId, Long version, String reason) {}

    private final SubstituteService substitutes;

    public SubstituteController(SubstituteService substitutes) {
        this.substitutes = substitutes;
    }

    @GetMapping
    public Options options(@PathVariable UUID periodId, @PathVariable UUID assignmentId) {
        return substitutes.options(periodId, assignmentId);
    }

    @PostMapping
    public ChartView substitute(@PathVariable UUID periodId, @PathVariable UUID assignmentId, @RequestBody SubstituteRequest body) {
        return substitutes.substitute(periodId, assignmentId, body.facultyId(), body.version(), body.reason());
    }
}
