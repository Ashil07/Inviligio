package com.invigilo.college;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.policy.PolicyService;
import com.invigilo.tenant.TenantContext;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The current college's profile and onboarding state. */
@Service
public class CollegeService {

    public record CollegeDetails(UUID id, String code, College.Status status, Instant onboardingCompletedAt,
                                 College.Profile profile, List<String> missingProfileFields) {
        static CollegeDetails of(College c) {
            return new CollegeDetails(c.getId(), c.getCode(), c.getStatus(), c.getOnboardingCompletedAt(),
                    c.profile(), c.missingProfileFields());
        }
    }

    private final CollegeRepository colleges;
    private final PolicyService policies;
    private final AuditService audit;
    private final Clock clock;

    public CollegeService(CollegeRepository colleges, PolicyService policies, AuditService audit, Clock clock) {
        this.colleges = colleges;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CollegeDetails current() {
        return CollegeDetails.of(load());
    }

    @Transactional
    public CollegeDetails updateProfile(College.Profile profile) {
        try {
            ZoneId.of(profile.timeZone());
        } catch (DateTimeException e) {
            throw ApiException.unprocessable("INVALID_TIME_ZONE", "Unknown time zone: " + profile.timeZone());
        }
        College college = load();
        College.Profile before = college.profile();
        college.updateProfile(profile);
        audit.record("COLLEGE_PROFILE_UPDATED", "college", college.getId(), before, profile, null);
        return CollegeDetails.of(college);
    }

    /** Ends onboarding once the institution profile is complete and a policy has been saved. */
    @Transactional
    public CollegeDetails completeOnboarding() {
        College college = load();
        if (college.getStatus() != College.Status.ONBOARDING) {
            return CollegeDetails.of(college);
        }
        List<String> missing = new ArrayList<>(college.missingProfileFields());
        if (!policies.hasSavedPolicy()) {
            missing.add("policy");
        }
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("ONBOARDING_INCOMPLETE",
                    "Complete these before finishing setup: " + String.join(", ", missing));
        }
        college.completeOnboarding(clock.instant());
        audit.record("COLLEGE_ONBOARDING_COMPLETED", "college", college.getId(), null, null, null);
        return CollegeDetails.of(college);
    }

    /** {@code College} is not tenant-filtered, so it is always loaded by the tenant from the token. */
    private College load() {
        return colleges.findById(TenantContext.requireCollegeId())
                .orElseThrow(() -> ApiException.notFound("COLLEGE_NOT_FOUND", "College not found"));
    }
}
