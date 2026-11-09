package com.invigilo.allocation.engine;

import com.invigilo.policy.PolicySettings;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable input of the allocation engine: everything it needs, nothing it must look up.
 * Built by {@code AllocationService} from the database; built by hand in unit tests.
 */
public record AllocationProblem(PolicySettings policy, List<SessionSlot> sessions, List<Candidate> candidates) {

    /** One examination session and the halls used in it. */
    public record SessionSlot(UUID id, LocalDate date, LocalTime start, LocalTime end, String label, List<HallSlot> halls) {}

    /**
     * One session hall: the subjects and departments examined in it (all seated exams) and the
     * positions it needs ({@code exam.Staffing}).
     */
    public record HallSlot(UUID id, UUID hallId, String hallCode, Set<UUID> subjectIds, Set<UUID> departmentIds,
                           int invigilators, int chiefs) {}

    /**
     * An active faculty member with everything that decides where they may go.
     *
     * @param visiting          VISITING or GUEST employment (subject to {@code visitingFacultyEligible})
     * @param chiefEligible     may take CHIEF positions (false also when restricted NOT_CHIEF)
     * @param dutyWeight        1.0 = normal share
     * @param maxPerDay         personal cap (restriction), null = none
     * @param maxPerPeriod      personal cap (restriction), null = none
     * @param historicalDuties  duties in earlier periods, for {@code historicalWorkloadPercent}
     */
    public record Candidate(UUID id, String code, String name, UUID departmentId, boolean visiting, boolean chiefEligible,
                            double dutyWeight, LocalDate joinedOn, Set<UUID> subjectIds, Set<UUID> excludedHallIds,
                            Set<UUID> excludedDepartmentIds, Integer maxPerDay, Integer maxPerPeriod,
                            Set<UUID> unavailableSessionIds, double historicalDuties) {}
}
