package com.invigilo.allocation.engine;

import com.invigilo.allocation.engine.AllocationEngine.Assigned;
import com.invigilo.allocation.engine.AllocationEngine.Position;
import com.invigilo.allocation.engine.AllocationProblem.Candidate;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.AllocationResult.Unfilled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Checks changes to an existing chart with exactly the rules and costs of generation
 * (docs/ALLOCATION_ENGINE.md §5): the manual editor, swaps and substitutes all go through here.
 *
 * <p>Built from the problem and the chart's current placements. Placements of faculty who are no
 * longer candidates (inactive) or of halls the problem does not know are skipped and reported by
 * {@link #skipped()}; the caller decides what to do with them. Pure and deterministic like the
 * engine. Not thread-safe: build one per request.
 */
public final class ChartChecker {

    /** One faculty member in one position of a chart. */
    public record Placement(UUID sessionHallId, UUID facultyId, Role role) {}

    /** Hard rules broken (an empty list means allowed) and soft costs accepted, in plain language. */
    public record Check(List<Reason> blocking, List<String> warnings) {
        public boolean allowed() {
            return blocking.isEmpty();
        }
    }

    /** A faculty member who could take a position, cheapest first. */
    public record Alternative(UUID facultyId, int duties, List<String> warnings) {}

    /** A placement that breaks hard rules against the rest of the chart. */
    public record Violation(Placement placement, List<Reason> reasons) {}

    private final AllocationEngine.Run run;
    private final List<Placement> skipped = new ArrayList<>();

    public ChartChecker(AllocationProblem problem, List<Placement> placements) {
        this.run = new AllocationEngine.Run(problem);
        run.prepareForChecks();
        placements.stream()
                .sorted(Comparator.comparing((Placement p) -> p.sessionHallId()).thenComparing(Placement::role)
                        .thenComparing(Placement::facultyId))
                .forEach(this::load);
    }

    private void load(Placement placement) {
        Candidate c = run.candidate(placement.facultyId());
        if (c == null || !run.knowsHall(placement.sessionHallId())) {
            skipped.add(placement);
            return;
        }
        run.assign(c, run.vacantPosition(placement.sessionHallId(), placement.role()), null);
    }

    /** Placements left out of every check: faculty no longer active, or halls not in the problem. */
    public List<Placement> skipped() {
        return List.copyOf(skipped);
    }

    public boolean isCandidate(UUID facultyId) {
        return run.candidate(facultyId) != null;
    }

    public boolean knowsHall(UUID sessionHallId) {
        return run.knowsHall(sessionHallId);
    }

    /** Duties this faculty member holds in the chart as loaded. */
    public int duties(UUID facultyId) {
        return run.candidate(facultyId) == null ? 0 : run.dutyCount(facultyId);
    }

    /**
     * Could {@code facultyId} take a position of this hall and role? With {@code replacing}, that
     * person's position is checked as if they had left it; otherwise a vacant seat is used.
     */
    public Check check(UUID sessionHallId, Role role, UUID facultyId, Placement replacing) {
        Candidate c = requireCandidate(facultyId);
        return withSeat(sessionHallId, role, replacing, p -> {
            List<Reason> blocking = run.reasons(c, p, true);
            List<String> warnings = new ArrayList<>();
            run.cost(c, run.indexOf(c), p, warnings);
            workloadWarning(c).ifPresent(warnings::add);
            return new Check(List.copyOf(blocking), List.copyOf(warnings));
        });
    }

    /** Up to {@code limit} faculty who could take the position, cheapest first (same costs as generation). */
    public List<Alternative> alternatives(UUID sessionHallId, Role role, Placement replacing, int limit) {
        return withSeat(sessionHallId, role, replacing, p -> {
            record Ranked(Candidate candidate, long cost, List<String> notes) {}
            List<Ranked> ranked = new ArrayList<>();
            for (Candidate c : run.candidates()) {
                if (replacing != null && c.id().equals(replacing.facultyId())) continue;
                if (!run.reasons(c, p, false).isEmpty()) continue;
                List<String> notes = new ArrayList<>();
                long cost = run.cost(c, run.indexOf(c), p, notes);
                ranked.add(new Ranked(c, cost, notes));
            }
            return ranked.stream()
                    .sorted(Comparator.comparingLong(Ranked::cost).thenComparing(r -> r.candidate().code())
                            .thenComparing(r -> r.candidate().id()))
                    .limit(limit)
                    .map(r -> new Alternative(r.candidate().id(), run.dutyCount(r.candidate().id()), List.copyOf(r.notes())))
                    .toList();
        });
    }

    /** Every loaded placement that breaks a hard rule against the others. */
    public List<Violation> violations() {
        return run.violations().stream()
                .map(v -> new Violation(new Placement(v.position().hall().id(), v.candidate().id(), v.position().role()),
                        v.reasons()))
                .toList();
    }

    /** Why a vacant position of this hall and role is hard to fill, as the engine explains unfilled positions. */
    public Unfilled explainOpen(UUID sessionHallId, Role role, int missing) {
        return run.explain(run.vacantPosition(sessionHallId, role), missing);
    }

    // ---- helpers ----

    private Candidate requireCandidate(UUID facultyId) {
        Candidate c = run.candidate(facultyId);
        if (c == null) throw new IllegalArgumentException("Not an active candidate: " + facultyId);
        return c;
    }

    /** Runs {@code body} on the seat being decided, restoring the chart afterwards. */
    private <T> T withSeat(UUID sessionHallId, Role role, Placement replacing, Function<Position, T> body) {
        if (!run.knowsHall(sessionHallId)) throw new IllegalArgumentException("Unknown session hall: " + sessionHallId);
        if (replacing == null) return body.apply(run.vacantPosition(sessionHallId, role));
        Position seat = run.heldBy(replacing.sessionHallId(), replacing.role(), replacing.facultyId());
        if (seat == null) throw new IllegalArgumentException("Not in the chart: " + replacing);
        Assigned original = run.unassign(seat);
        try {
            return body.apply(seat);
        } finally {
            run.assign(original.candidate(), seat, original.explanation());
        }
    }

    /** How a person's duties compare with an even share of the period's duties. */
    public enum Load { UNDER, BALANCED, OVER }

    /**
     * The workload rule shared by the editor's warning and the HOD dashboard: an even share is
     * {@code average × dutyWeight}; more than one duty above it (rounded up) is {@code OVER}, more
     * than one below it (rounded down) is {@code UNDER}.
     */
    public static Load load(int duties, double average, double dutyWeight) {
        double share = average * dutyWeight;
        if (duties > Math.ceil(share) + 1) return Load.OVER;
        if (duties < Math.floor(share) - 1) return Load.UNDER;
        return Load.BALANCED;
    }

    /**
     * Warns when the change would leave this person well above an even share: more than one duty
     * over the (weight-adjusted) average once the duty is added.
     */
    private Optional<String> workloadWarning(Candidate c) {
        List<Candidate> all = run.candidates();
        if (all.isEmpty()) return Optional.empty();
        int after = run.dutyCount(c.id()) + 1;
        double total = 1;
        for (Candidate other : all) total += run.dutyCount(other.id());
        double mean = total / all.size();
        if (load(after, mean, c.dutyWeight()) != Load.OVER) return Optional.empty();
        return Optional.of("would have " + after + " duties in this period; the average is "
                + Math.round(mean * 10) / 10.0);
    }
}
