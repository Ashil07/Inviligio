package com.invigilo.policy;

import com.invigilo.policy.PolicyService.PolicyVersionSummary;
import com.invigilo.policy.PolicyService.PolicyView;
import com.invigilo.tenant.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policy")
public class PolicyController {

    public record SavePolicyRequest(@NotNull @Min(0) Integer basedOnVersion, @Valid @NotNull PolicySettings settings) {}

    private final PolicyService policies;

    public PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    /** Any member may read the current rules (faculty need to know e.g. whether swaps are allowed). */
    @GetMapping
    public PolicyView current() {
        return policies.current();
    }

    @PutMapping
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public PolicyView save(@Valid @RequestBody SavePolicyRequest request) {
        return policies.save(request.basedOnVersion(), request.settings(), CurrentUser.require().userId());
    }

    @GetMapping("/versions")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public List<PolicyVersionSummary> versions() {
        return policies.versions();
    }

    @GetMapping("/versions/{version}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public PolicyView version(@PathVariable int version) {
        return policies.version(version);
    }
}
