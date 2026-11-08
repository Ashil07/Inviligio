package com.invigilo.college;

import com.invigilo.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A tenant. Not itself tenant-scoped: load it by the current tenant id
 * ({@code TenantContext.requireCollegeId()}), never by an id taken from a request.
 */
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

    private String affiliation;
    private String address;
    private String city;
    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "academic_year")
    private String academicYear;

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

    public void updateProfile(Profile profile) {
        this.name = profile.name();
        this.affiliation = profile.affiliation();
        this.address = profile.address();
        this.city = profile.city();
        this.state = profile.state();
        this.postalCode = profile.postalCode();
        this.contactEmail = profile.contactEmail();
        this.contactPhone = profile.contactPhone();
        this.academicYear = profile.academicYear();
        this.timeZone = profile.timeZone();
    }

    /** Institution fields that onboarding requires; empty when the profile is complete. */
    public List<String> missingProfileFields() {
        List<String> missing = new ArrayList<>();
        if (isBlank(affiliation)) missing.add("affiliation");
        if (isBlank(address)) missing.add("address");
        if (isBlank(city)) missing.add("city");
        if (isBlank(state)) missing.add("state");
        if (isBlank(academicYear)) missing.add("academicYear");
        return missing;
    }

    void completeOnboarding(Instant at) {
        this.status = Status.ACTIVE;
        this.onboardingCompletedAt = at;
    }

    public Profile profile() {
        return new Profile(name, affiliation, address, city, state, postalCode, contactEmail, contactPhone,
                academicYear, timeZone);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Editable institution details (already validated and trimmed). */
    public record Profile(String name, String affiliation, String address, String city, String state,
                          String postalCode, String contactEmail, String contactPhone, String academicYear,
                          String timeZone) {}

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getTimeZone() { return timeZone; }
    public Status getStatus() { return status; }
    public Instant getOnboardingCompletedAt() { return onboardingCompletedAt; }
    public String getAffiliation() { return affiliation; }
    public String getAddress() { return address; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getPostalCode() { return postalCode; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public String getAcademicYear() { return academicYear; }
}
