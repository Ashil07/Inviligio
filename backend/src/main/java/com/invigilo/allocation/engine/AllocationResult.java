package com.invigilo.allocation.engine;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Output of the engine. Assignments never violate a hard rule (checked before returning). */
public record AllocationResult(List<Assignment> assignments, List<Unfilled> unfilled, Stats stats) {

    public enum Role { CHIEF, INVIGILATOR }

    /**
     * @param eligibleCandidates how many faculty could legally have taken the position
     * @param dutiesBefore       the faculty member's duties in this period before this one
     * @param penalties          soft-rule costs accepted for this choice (plain language)
     */
    public record Explanation(int eligibleCandidates, int dutiesBefore, List<String> penalties) {}

    public record Assignment(UUID sessionId, UUID sessionHallId, UUID facultyId, Role role, Explanation explanation) {}

    /**
     * Positions of one session hall and role that could not be filled.
     *
     * @param eliminated  how many faculty were excluded for each first-failing reason
     * @param suggestions concrete changes that would make faculty eligible
     */
    public record Unfilled(UUID sessionId, UUID sessionHallId, Role role, int missing, int totalFaculty,
                           Map<Reason, Integer> eliminated, List<String> suggestions) {}

    public record Stats(int positions, int filled, int faculty, int minDuties, int maxDuties, double meanDuties,
                        double stdDevDuties, long elapsedMillis) {}
}
