package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.allocation.RemunerationService.Pay;
import com.invigilo.allocation.RemunerationService.RateInput;
import com.invigilo.allocation.RemunerationService.RateView;
import com.invigilo.tenant.CurrentUser;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Remuneration rates, pay per period and attendance (M17). Exam cell only. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class RemunerationController {

    /** {@code absent} true marks the person absent (not paid); false marks them present again. */
    public record AttendanceRequest(boolean absent, Long version, String reason) {}

    private final RemunerationService remuneration;

    public RemunerationController(RemunerationService remuneration) {
        this.remuneration = remuneration;
    }

    @GetMapping("/remuneration/rates")
    public List<RateView> rates() {
        return remuneration.rates();
    }

    @PostMapping("/remuneration/rates")
    @ResponseStatus(HttpStatus.CREATED)
    public RateView addRate(@RequestBody RateInput input) {
        return remuneration.addRate(input, CurrentUser.require());
    }

    @DeleteMapping("/remuneration/rates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRate(@PathVariable UUID id, @RequestParam Long version) {
        remuneration.deleteRate(id, version);
    }

    @GetMapping("/remuneration")
    public Pay pay(@RequestParam(required = false) UUID periodId) {
        return remuneration.pay(periodId);
    }

    @PutMapping("/exam-periods/{periodId}/duty-chart/assignments/{assignmentId}/attendance")
    public ChartView attendance(@PathVariable UUID periodId, @PathVariable UUID assignmentId, @RequestBody AttendanceRequest body) {
        return remuneration.markAttendance(periodId, assignmentId, body.absent(), body.version(), body.reason());
    }
}
