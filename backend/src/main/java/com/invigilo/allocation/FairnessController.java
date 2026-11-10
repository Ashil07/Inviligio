package com.invigilo.allocation;

import com.invigilo.allocation.FairnessService.Fairness;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fairness analytics over published duty charts (M15). Exam cell only. */
@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class FairnessController {

    private final FairnessService fairness;

    public FairnessController(FairnessService fairness) {
        this.fairness = fairness;
    }

    @GetMapping("/fairness")
    public Fairness fairness(@RequestParam(required = false) UUID periodId) {
        return fairness.fairness(periodId);
    }
}
