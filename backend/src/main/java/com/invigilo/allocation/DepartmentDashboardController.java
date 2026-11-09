package com.invigilo.allocation;

import com.invigilo.allocation.DepartmentDashboardService.Overview;
import com.invigilo.tenant.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The HOD dashboard (M12): the head's own department in a published duty chart. */
@RestController
@RequestMapping("/api/v1/department")
public class DepartmentDashboardController {

    private final DepartmentDashboardService dashboard;

    public DepartmentDashboardController(DepartmentDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('HOD')")
    public Overview overview(@RequestParam(required = false) UUID periodId) {
        return dashboard.overview(CurrentUser.require(), periodId);
    }
}
