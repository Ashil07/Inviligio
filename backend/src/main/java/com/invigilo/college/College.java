package com.invigilo.college;

import com.invigilo.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/** A tenant. Not itself tenant-scoped; tenant-owned rows reference it through {@code college_id}. */
@Entity
@Table(name = "college")
public class College extends BaseEntity {

    public enum Status { ONBOARDING, ACTIVE, SUSPENDED }

    public static final String DEFAULT_TIME_ZONE = "Asia/Kolkata";

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "time_zone", nullable = false)
    private String timeZone = DEFAULT_TIME_ZONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ONBOARDING;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    protected College() {}

    public College(String code, String name) {
        this.code = normalizeCode(code);
        this.name = name.trim();
    }

    public static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /** Suspended colleges cannot be signed in to. */
    public boolean allowsSignIn() {
        return status != Status.SUSPENDED;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getOnboardingCompletedAt() {
        return onboardingCompletedAt;
    }
}
