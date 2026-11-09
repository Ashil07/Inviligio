package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.tenant.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Duty-chart generation and review (exam cell). Faculty and HOD views come with their dashboards. */
@RestController
@RequestMapping("/api/v1/exam-periods/{periodId}/duty-chart")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class AllocationController {

    private final AllocationService allocation;

    public AllocationController(AllocationService allocation) {
        this.allocation = allocation;
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
}
