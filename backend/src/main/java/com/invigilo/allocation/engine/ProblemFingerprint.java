package com.invigilo.allocation.engine;

import com.invigilo.allocation.engine.AllocationProblem.Candidate;
import com.invigilo.allocation.engine.AllocationProblem.HallSlot;
import com.invigilo.allocation.engine.AllocationProblem.SessionSlot;
import com.invigilo.common.security.Tokens;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SHA-256 of a canonical text form of a problem. Equal inputs give equal fingerprints regardless
 * of collection order, so a stored chart can tell whether its inputs have changed since.
 */
public final class ProblemFingerprint {

    private ProblemFingerprint() {}

    public static String of(AllocationProblem problem) {
        StringBuilder sb = new StringBuilder();
        sb.append(problem.policy()).append('\n');
        problem.sessions().stream().sorted(Comparator.comparing(SessionSlot::id)).forEach(s -> {
            sb.append("S|").append(s.id()).append('|').append(s.date()).append('|').append(s.start()).append('|')
                    .append(s.end()).append('\n');
            s.halls().stream().sorted(Comparator.comparing(HallSlot::id)).forEach(h -> sb.append("H|").append(h.id())
                    .append('|').append(h.hallId()).append('|').append(ids(h.subjectIds())).append('|')
                    .append(ids(h.departmentIds())).append('|').append(h.invigilators()).append('|').append(h.chiefs())
                    .append('\n'));
        });
        problem.candidates().stream().sorted(Comparator.comparing(Candidate::id)).forEach(c -> sb.append("C|")
                .append(c.id()).append('|').append(c.departmentId()).append('|').append(c.visiting()).append('|')
                .append(c.chiefEligible()).append('|').append(c.dutyWeight()).append('|').append(c.joinedOn()).append('|')
                .append(ids(c.subjectIds())).append('|').append(ids(c.excludedHallIds())).append('|')
                .append(ids(c.excludedDepartmentIds())).append('|').append(c.maxPerDay()).append('|')
                .append(c.maxPerPeriod()).append('|').append(ids(c.unavailableSessionIds())).append('|')
                .append(c.historicalDuties()).append('\n'));
        return Tokens.sha256Hex(sb.toString());
    }

    private static String ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }
}
