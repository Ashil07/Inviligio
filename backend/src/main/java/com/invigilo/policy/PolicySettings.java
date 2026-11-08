package com.invigilo.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A college's examination policy: the configurable inputs of the allocation engine and of the
 * availability and swap workflows. Stored as JSON in {@code college_policy.settings}, one immutable
 * row per version. See docs/ALLOCATION_ENGINE.md §3 for how each rule is applied.
 *
 * <p>Every field is a wrapper type with {@code @NotNull} so API clients must state each rule
 * explicitly; a silently defaulted {@code false} would change allocation behaviour unnoticed.
 * Adding a field requires a Flyway migration that backfills it into existing rows.
 */
public record PolicySettings(
        @Valid @NotNull FacultyRules faculty,
        @Valid @NotNull WorkloadRules workload,
        @Valid @NotNull StaffingRules staffing,
        @Valid @NotNull AvailabilityRules availability,
        @Valid @NotNull SwapRules swaps) {

    /** How a configurable rule is applied: never violated, minimised, or ignored. */
    public enum RuleMode { HARD, SOFT, OFF }

    /** May faculty invigilate exams conducted by their own department? */
    public enum DepartmentRule { ALLOW, AVOID, FORBID }

    public enum ChiefInvigilatorRule { NONE, PER_HALL }

    public record FacultyRules(
            /* false = HARD rule: never invigilate a hall seating a subject one teaches. */
            @NotNull Boolean allowOwnSubject,
            @NotNull DepartmentRule ownDepartmentExams,
            @NotNull Boolean visitingFacultyEligible,
            @NotNull Boolean seniorsGetFewerDuties,
            @NotNull Boolean preferSeniorsAsChief,
            @NotNull ChiefInvigilatorRule chiefInvigilator,
            @NotNull Boolean chiefCountsTowardsStaffing) {}

    /** A limit with a mode, e.g. "at most 2 consecutive sessions, HARD". */
    public record Limit(@NotNull RuleMode mode, @NotNull @Min(1) @Max(20) Integer max) {}

    public record WorkloadRules(
            @NotNull @Min(1) @Max(6) Integer maxDutiesPerDay,
            @Valid @NotNull Limit consecutiveSessions,
            @NotNull @Min(0) @Max(720) Integer minRestMinutes,
            @Valid @NotNull Limit consecutiveDays,
            /* null = no per-period cap. */
            @Min(1) @Max(200) Integer maxDutiesPerPeriod,
            /* 0-10: how strongly to equalise duty counts. */
            @NotNull @Min(0) @Max(10) Integer equalDistributionWeight,
            /* 0-100: how much of previous periods' workload counts towards fairness. */
            @NotNull @Min(0) @Max(100) Integer historicalWorkloadPercent,
            @NotNull @Min(0) @Max(10) Integer avoidRepeatHallWeight) {}

    public record StaffingRules(
            @NotNull @Min(1) @Max(500) Integer studentsPerInvigilator,
            /* null = practical exams use the theory ratio. */
            @Min(1) @Max(500) Integer practicalStudentsPerInvigilator,
            @NotNull @Min(1) @Max(20) Integer minInvigilatorsPerHall) {}

    public record AvailabilityRules(
            @NotNull Boolean allowFullDayUnavailability,
            @NotNull Boolean allowSessionUnavailability,
            @NotNull Boolean leaveRequiresHodApproval,
            @NotNull Boolean adminCanOverrideUnavailability) {}

    public record SwapRules(
            @NotNull Boolean swapsEnabled,
            @NotNull Boolean recipientMustAccept,
            @NotNull Boolean hodApprovalRequired,
            @NotNull Boolean adminCanReassignDirectly) {}

    /** Starting point offered during onboarding. Conservative: protects fairness and subject integrity. */
    public static PolicySettings defaults() {
        return new PolicySettings(
                new FacultyRules(false, DepartmentRule.ALLOW, true, false, false, ChiefInvigilatorRule.NONE, true),
                new WorkloadRules(2, new Limit(RuleMode.HARD, 2), 0, new Limit(RuleMode.OFF, 3), null, 8, 30, 2),
                new StaffingRules(30, null, 1),
                new AvailabilityRules(true, true, false, true),
                new SwapRules(true, true, false, true));
    }
}
