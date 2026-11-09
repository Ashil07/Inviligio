package com.invigilo.allocation.engine;

/**
 * Why a faculty member cannot take a position. Checked in this order; the first match is the
 * reported reason. {@code configurable} reasons come from the college policy and can be relaxed,
 * which is what unfilled-position suggestions are built from.
 */
public enum Reason {
    UNAVAILABLE("on approved leave or unavailable", false),
    VISITING_NOT_ELIGIBLE("visiting or guest faculty, who are not eligible", true),
    EXCLUDED_HALL("personally restricted from this hall", false),
    EXCLUDED_DEPARTMENT("personally restricted from this department's exams", false),
    OWN_SUBJECT("teach a subject examined in this hall", true),
    OWN_DEPARTMENT("belong to a department whose exam is in this hall", true),
    NOT_CHIEF_ELIGIBLE("not eligible to be chief invigilator", false),
    BUSY_IN_SESSION("already on duty at this time", false),
    MAX_PER_DAY("at the maximum number of duties for the day", true),
    MIN_REST("would not get the minimum rest between duties", true),
    MAX_CONSECUTIVE_SESSIONS("would exceed the consecutive-session limit", true),
    MAX_CONSECUTIVE_DAYS("would exceed the consecutive-day limit", true),
    MAX_PER_PERIOD("at the maximum number of duties for the period", true);

    private final String description;
    private final boolean configurable;

    Reason(String description, boolean configurable) {
        this.description = description;
        this.configurable = configurable;
    }

    /** Completes "N faculty are ...". */
    public String description() {
        return description;
    }

    public boolean configurable() {
        return configurable;
    }
}
