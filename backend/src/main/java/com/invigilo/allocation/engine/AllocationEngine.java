package com.invigilo.allocation.engine;

import com.invigilo.allocation.engine.AllocationProblem.Candidate;
import com.invigilo.allocation.engine.AllocationProblem.HallSlot;
import com.invigilo.allocation.engine.AllocationProblem.SessionSlot;
import com.invigilo.allocation.engine.AllocationResult.Assignment;
import com.invigilo.allocation.engine.AllocationResult.Explanation;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.AllocationResult.Stats;
import com.invigilo.allocation.engine.AllocationResult.Unfilled;
import com.invigilo.policy.PolicySettings;
import com.invigilo.policy.PolicySettings.DepartmentRule;
import com.invigilo.policy.PolicySettings.RuleMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Allocates invigilators to positions (docs/ALLOCATION_ENGINE.md §4).
 *
 * <ol>
 *   <li>Positions per session hall (chief first, then invigilators).</li>
 *   <li>Sessions grouped into overlap groups (same date, intersecting times), chronologically.</li>
 *   <li>Per group, an optimal min-cost assignment (Hungarian) over the faculty allowed by every
 *       hard rule; the cost is the sum of soft penalties plus a look-ahead scarcity term.</li>
 *   <li>A bounded repair pass for positions left unfilled by cross-session limits.</li>
 *   <li>A final validation of every assignment; a violation is a bug and aborts the run.</li>
 * </ol>
 *
 * Pure and deterministic: no Spring, no database, no clock, no randomness; everything is sorted
 * by stable keys, so the same problem always produces the same chart.
 */
public final class AllocationEngine {

    static final long UNFILLED_COST = 1_000_000_000L;
    static final long FORBIDDEN_COST = 1_000_000_000_000_000L;
    static final int LOOKAHEAD_GROUPS = 3;
    private static final EnumSet<Reason> CROSS_SESSION = EnumSet.of(Reason.MAX_PER_DAY, Reason.MIN_REST,
            Reason.MAX_CONSECUTIVE_SESSIONS, Reason.MAX_CONSECUTIVE_DAYS, Reason.MAX_PER_PERIOD);

    public AllocationResult allocate(AllocationProblem problem) {
        return new Run(problem).execute();
    }

    /** A seat to fill. {@code index} is the stable order of all positions. */
    record Position(int index, int group, SessionSlot session, HallSlot hall, Role role) {}

    record Assigned(Candidate candidate, Explanation explanation) {}

    /** An assignment that breaks hard rules, found by {@link Run#violations()}. */
    record Violation(Position position, Candidate candidate, List<Reason> reasons) {}

    /**
     * One allocation or one chart being checked. Package-private so {@link ChartChecker} can reuse
     * exactly the same hard rules and soft costs as generation.
     */
    static final class Run {
        private final PolicySettings policy;
        private final List<SessionSlot> sessions;
        private final List<Candidate> candidates;
        private final List<Position> positions = new ArrayList<>();
        private final List<List<Position>> positionsByGroup = new ArrayList<>();
        private final Map<UUID, Integer> groupOf = new HashMap<>();
        private final Map<UUID, Integer> dayOrder = new HashMap<>();
        private final Map<UUID, Double> juniority = new HashMap<>();
        private final Map<Integer, Assigned> assigned = new HashMap<>();
        private final Map<UUID, List<Position>> duties = new HashMap<>();
        private final Map<Integer, Set<UUID>> groupMembers = new HashMap<>();
        private final Map<UUID, SessionSlot> sessionOfHall = new HashMap<>();
        private final Map<UUID, HallSlot> hallById = new HashMap<>();
        private final Map<UUID, Integer> candidateIndex = new HashMap<>();
        private double[][] scarcity;

        Run(AllocationProblem problem) {
            this.policy = problem.policy();
            this.sessions = problem.sessions().stream()
                    .sorted(Comparator.comparing(SessionSlot::date).thenComparing(SessionSlot::start)
                            .thenComparing(SessionSlot::end).thenComparing(SessionSlot::id))
                    .map(s -> new SessionSlot(s.id(), s.date(), s.start(), s.end(), s.label(), s.halls().stream()
                            .sorted(Comparator.comparing(HallSlot::hallCode).thenComparing(HallSlot::id)).toList()))
                    .toList();
            this.candidates = problem.candidates().stream()
                    .sorted(Comparator.comparing(Candidate::code).thenComparing(Candidate::id))
                    .toList();
            candidates.forEach(c -> duties.put(c.id(), new ArrayList<>()));
            for (int i = 0; i < candidates.size(); i++) candidateIndex.put(candidates.get(i).id(), i);
            for (SessionSlot s : sessions) {
                for (HallSlot h : s.halls()) {
                    sessionOfHall.put(h.id(), s);
                    hallById.put(h.id(), h);
                }
            }
        }

        /**
         * Sets up positions and seniority for checking an existing chart. No look-ahead: an edit is
         * judged on the chart as it is, not on positions still to be filled.
         */
        void prepareForChecks() {
            buildGroupsAndPositions();
            computeSeniority();
            scarcity = new double[positionsByGroup.size()][candidates.size()];
        }

        AllocationResult execute() {
            long started = System.nanoTime();
            buildGroupsAndPositions();
            computeSeniority();
            computeScarcity();
            for (int g = 0; g < positionsByGroup.size(); g++) solveGroup(g);
            repair();
            validate();
            List<Unfilled> unfilled = explainUnfilled();
            List<Assignment> result = new ArrayList<>();
            assigned.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        Position p = positions.get(e.getKey());
                        result.add(new Assignment(p.session().id(), p.hall().id(), e.getValue().candidate().id(), p.role(),
                                e.getValue().explanation()));
                    });
            return new AllocationResult(result, unfilled, stats(Duration.ofNanos(System.nanoTime() - started).toMillis()));
        }

        // ---- setup ----

        private void buildGroupsAndPositions() {
            // Overlap groups: connected components of intersecting sessions on the same date.
            int group = -1;
            LocalDate groupDate = null;
            java.time.LocalTime groupEnd = null;
            for (SessionSlot s : sessions) {
                boolean joins = groupDate != null && groupDate.equals(s.date()) && s.start().isBefore(groupEnd);
                if (!joins) {
                    group++;
                    positionsByGroup.add(new ArrayList<>());
                    groupDate = s.date();
                    groupEnd = s.end();
                } else if (s.end().isAfter(groupEnd)) {
                    groupEnd = s.end();
                }
                groupOf.put(s.id(), group);
            }
            Map<LocalDate, Integer> nextOrder = new HashMap<>();
            for (SessionSlot s : sessions) {
                dayOrder.put(s.id(), nextOrder.merge(s.date(), 1, Integer::sum) - 1);
                int g = groupOf.get(s.id());
                for (HallSlot h : s.halls()) {
                    for (int i = 0; i < h.chiefs(); i++) addPosition(g, s, h, Role.CHIEF);
                    for (int i = 0; i < h.invigilators(); i++) addPosition(g, s, h, Role.INVIGILATOR);
                }
            }
        }

        private void addPosition(int group, SessionSlot s, HallSlot h, Role role) {
            Position p = new Position(positions.size(), group, s, h, role);
            positions.add(p);
            positionsByGroup.get(group).add(p);
        }

        /** 0 = most senior (earliest joining date), 1 = most junior or unknown. */
        private void computeSeniority() {
            List<Candidate> known = candidates.stream().filter(c -> c.joinedOn() != null)
                    .sorted(Comparator.comparing(Candidate::joinedOn).thenComparing(Candidate::code)).toList();
            for (int i = 0; i < known.size(); i++) {
                juniority.put(known.get(i).id(), known.size() == 1 ? 0.0 : (double) i / (known.size() - 1));
            }
            candidates.forEach(c -> juniority.putIfAbsent(c.id(), 1.0));
        }

        /**
         * How much each candidate is needed in the next few groups: the sum, over upcoming positions
         * they could take (static rules only), of 1 / number of such candidates. Scarce people are
         * kept back for the positions only they can fill.
         */
        private void computeScarcity() {
            int groups = positionsByGroup.size();
            scarcity = new double[groups][candidates.size()];
            List<int[]> staticEligible = new ArrayList<>();
            for (Position p : positions) {
                List<Integer> ok = new ArrayList<>();
                for (int ci = 0; ci < candidates.size(); ci++) {
                    if (staticReason(candidates.get(ci), p) == null) ok.add(ci);
                }
                staticEligible.add(ok.stream().mapToInt(Integer::intValue).toArray());
            }
            for (int g = 0; g < groups; g++) {
                for (int h = g + 1; h <= Math.min(groups - 1, g + LOOKAHEAD_GROUPS); h++) {
                    for (Position q : positionsByGroup.get(h)) {
                        int[] ok = staticEligible.get(q.index());
                        if (ok.length == 0) continue;
                        double share = 1.0 / ok.length;
                        for (int ci : ok) scarcity[g][ci] += share;
                    }
                }
            }
        }

        // ---- hard rules ----

        private Reason staticReason(Candidate c, Position p) {
            var faculty = policy.faculty();
            HallSlot h = p.hall();
            if (c.unavailableSessionIds().contains(p.session().id())) return Reason.UNAVAILABLE;
            if (c.visiting() && !faculty.visitingFacultyEligible()) return Reason.VISITING_NOT_ELIGIBLE;
            if (c.excludedHallIds().contains(h.hallId())) return Reason.EXCLUDED_HALL;
            if (!Collections.disjoint(c.excludedDepartmentIds(), h.departmentIds())) return Reason.EXCLUDED_DEPARTMENT;
            if (!faculty.allowOwnSubject() && !Collections.disjoint(c.subjectIds(), h.subjectIds())) return Reason.OWN_SUBJECT;
            if (faculty.ownDepartmentExams() == DepartmentRule.FORBID && h.departmentIds().contains(c.departmentId())) {
                return Reason.OWN_DEPARTMENT;
            }
            if (p.role() == Role.CHIEF && !c.chiefEligible()) return Reason.NOT_CHIEF_ELIGIBLE;
            return null;
        }

        /** Failing hard rules in {@link Reason} order; only the first unless {@code all}. */
        List<Reason> reasons(Candidate c, Position p, boolean all) {
            List<Reason> out = new ArrayList<>(2);
            if (all) {
                // Static rules can overlap; collect each one.
                var faculty = policy.faculty();
                HallSlot h = p.hall();
                if (c.unavailableSessionIds().contains(p.session().id())) out.add(Reason.UNAVAILABLE);
                if (c.visiting() && !faculty.visitingFacultyEligible()) out.add(Reason.VISITING_NOT_ELIGIBLE);
                if (c.excludedHallIds().contains(h.hallId())) out.add(Reason.EXCLUDED_HALL);
                if (!Collections.disjoint(c.excludedDepartmentIds(), h.departmentIds())) out.add(Reason.EXCLUDED_DEPARTMENT);
                if (!faculty.allowOwnSubject() && !Collections.disjoint(c.subjectIds(), h.subjectIds())) out.add(Reason.OWN_SUBJECT);
                if (faculty.ownDepartmentExams() == DepartmentRule.FORBID && h.departmentIds().contains(c.departmentId())) {
                    out.add(Reason.OWN_DEPARTMENT);
                }
                if (p.role() == Role.CHIEF && !c.chiefEligible()) out.add(Reason.NOT_CHIEF_ELIGIBLE);
            } else {
                Reason r = staticReason(c, p);
                if (r != null) return List.of(r);
            }
            var workload = policy.workload();
            List<Position> mine = duties.get(c.id());
            if (groupMembers.getOrDefault(p.group(), Set.of()).contains(c.id())
                    || mine.stream().anyMatch(d -> overlaps(d.session(), p.session()))) {
                if (stop(out, Reason.BUSY_IN_SESSION, all)) return out;
            }
            List<Position> sameDay = mine.stream().filter(d -> d.session().date().equals(p.session().date())).toList();
            int maxDay = c.maxPerDay() == null ? workload.maxDutiesPerDay() : Math.min(workload.maxDutiesPerDay(), c.maxPerDay());
            if (sameDay.size() >= maxDay && stop(out, Reason.MAX_PER_DAY, all)) return out;
            if (workload.minRestMinutes() > 0) {
                for (Position d : sameDay) {
                    long gap = gapMinutes(d.session(), p.session());
                    if (gap >= 0 && gap < workload.minRestMinutes()) {
                        if (stop(out, Reason.MIN_REST, all)) return out;
                        break;
                    }
                }
            }
            var sessionsLimit = workload.consecutiveSessions();
            if (sessionsLimit.mode() == RuleMode.HARD && sessionRun(sameDay, p) > sessionsLimit.max()
                    && stop(out, Reason.MAX_CONSECUTIVE_SESSIONS, all)) return out;
            var daysLimit = workload.consecutiveDays();
            if (daysLimit.mode() == RuleMode.HARD && dayRun(mine, p) > daysLimit.max()
                    && stop(out, Reason.MAX_CONSECUTIVE_DAYS, all)) return out;
            Integer policyMax = workload.maxDutiesPerPeriod();
            Integer maxPeriod = policyMax == null ? c.maxPerPeriod()
                    : c.maxPerPeriod() == null ? policyMax : Integer.valueOf(Math.min(policyMax, c.maxPerPeriod()));
            if (maxPeriod != null && mine.size() >= maxPeriod) stop(out, Reason.MAX_PER_PERIOD, all);
            return out;
        }

        private static boolean stop(List<Reason> out, Reason r, boolean all) {
            out.add(r);
            return !all;
        }

        private static boolean overlaps(SessionSlot a, SessionSlot b) {
            return a.date().equals(b.date()) && a.start().isBefore(b.end()) && b.start().isBefore(a.end());
        }

        /** Minutes between two same-day sessions, or -1 when they overlap. */
        private static long gapMinutes(SessionSlot a, SessionSlot b) {
            if (!a.end().isAfter(b.start())) return Duration.between(a.end(), b.start()).toMinutes();
            if (!b.end().isAfter(a.start())) return Duration.between(b.end(), a.start()).toMinutes();
            return -1;
        }

        /** Length of the run of consecutive same-day sessions with duty that {@code p} would be part of. */
        private int sessionRun(List<Position> sameDay, Position p) {
            Set<Integer> orders = new HashSet<>();
            sameDay.forEach(d -> orders.add(dayOrder.get(d.session().id())));
            int at = dayOrder.get(p.session().id());
            orders.add(at);
            int run = 1;
            for (int i = at - 1; orders.contains(i); i--) run++;
            for (int i = at + 1; orders.contains(i); i++) run++;
            return run;
        }

        /** Length of the run of consecutive calendar days with duty that {@code p} would be part of. */
        private static int dayRun(List<Position> mine, Position p) {
            Set<LocalDate> days = new HashSet<>();
            mine.forEach(d -> days.add(d.session().date()));
            LocalDate at = p.session().date();
            days.add(at);
            int run = 1;
            for (LocalDate d = at.minusDays(1); days.contains(d); d = d.minusDays(1)) run++;
            for (LocalDate d = at.plusDays(1); days.contains(d); d = d.plusDays(1)) run++;
            return run;
        }

        // ---- soft costs ----

        long cost(Candidate c, int candidateIndex, Position p, List<String> notes) {
            var workload = policy.workload();
            List<Position> mine = duties.get(c.id());
            double seniorityFactor = policy.faculty().seniorsGetFewerDuties() ? 1 - 0.4 * (1 - juniority.get(c.id())) : 1;
            double weight = Math.max(0.1, c.dutyWeight() * seniorityFactor);
            double load = (mine.size() + c.historicalDuties() * workload.historicalWorkloadPercent() / 100.0) / weight;
            // Quadratic marginal load cost drives equal shares; the base keeps a tie-breaker when the weight is 0.
            long cost = Math.round((workload.equalDistributionWeight() * 100 + 10) * (2 * load + 1));
            List<Position> sameDay = mine.stream().filter(d -> d.session().date().equals(p.session().date())).toList();
            var sessionsLimit = workload.consecutiveSessions();
            if (sessionsLimit.mode() == RuleMode.SOFT && sessionRun(sameDay, p) > sessionsLimit.max()) {
                cost += 400;
                note(notes, "more than " + sessionsLimit.max() + " consecutive sessions");
            }
            var daysLimit = workload.consecutiveDays();
            if (daysLimit.mode() == RuleMode.SOFT && dayRun(mine, p) > daysLimit.max()) {
                cost += 400;
                note(notes, "more than " + daysLimit.max() + " consecutive days with duty");
            }
            if (policy.faculty().ownDepartmentExams() == DepartmentRule.AVOID && p.hall().departmentIds().contains(c.departmentId())) {
                cost += 600;
                note(notes, "invigilates an exam of their own department");
            }
            long sameHall = mine.stream().filter(d -> d.hall().hallId().equals(p.hall().hallId())).count();
            if (sameHall > 0 && workload.avoidRepeatHallWeight() > 0) {
                cost += sameHall * workload.avoidRepeatHallWeight() * 30L;
                note(notes, "already had " + sameHall + " duty(ies) in hall " + p.hall().hallCode());
            }
            if (p.role() == Role.CHIEF && policy.faculty().preferSeniorsAsChief()) {
                cost += Math.round(300 * juniority.get(c.id()));
            }
            cost += Math.round(150 * scarcity[p.group()][candidateIndex]);
            return cost;
        }

        private static void note(List<String> notes, String text) {
            if (notes != null) notes.add(text);
        }

        // ---- solving ----

        private void solveGroup(int g) {
            List<Position> rows = positionsByGroup.get(g);
            if (rows.isEmpty()) return;
            int m = candidates.size();
            long[][] matrix = new long[rows.size()][m + rows.size()];
            int[] eligible = new int[rows.size()];
            for (int r = 0; r < rows.size(); r++) {
                Position p = rows.get(r);
                for (int ci = 0; ci < m; ci++) {
                    Candidate c = candidates.get(ci);
                    if (reasons(c, p, false).isEmpty()) {
                        matrix[r][ci] = cost(c, ci, p, null);
                        eligible[r]++;
                    } else {
                        matrix[r][ci] = FORBIDDEN_COST;
                    }
                }
                for (int d = 0; d < rows.size(); d++) matrix[r][m + d] = UNFILLED_COST;
            }
            int[] match = Hungarian.solve(matrix);
            List<Runnable> commits = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) {
                int col = match[r];
                Position p = rows.get(r);
                if (col < m && matrix[r][col] < UNFILLED_COST) {
                    Candidate c = candidates.get(col);
                    List<String> notes = new ArrayList<>();
                    cost(c, col, p, notes);
                    Explanation why = new Explanation(eligible[r], duties.get(c.id()).size(), List.copyOf(notes));
                    commits.add(() -> assign(c, p, why));
                }
            }
            commits.forEach(Runnable::run);
        }

        void assign(Candidate c, Position p, Explanation why) {
            assigned.put(p.index(), new Assigned(c, why));
            duties.get(c.id()).add(p);
            groupMembers.computeIfAbsent(p.group(), k -> new HashSet<>()).add(c.id());
        }

        Assigned unassign(Position p) {
            Assigned a = assigned.remove(p.index());
            duties.get(a.candidate().id()).remove(p);
            groupMembers.get(p.group()).remove(a.candidate().id());
            return a;
        }

        // ---- lookups for chart checks ----

        Candidate candidate(UUID facultyId) {
            Integer i = candidateIndex.get(facultyId);
            return i == null ? null : candidates.get(i);
        }

        int indexOf(Candidate c) {
            return candidateIndex.get(c.id());
        }

        List<Candidate> candidates() {
            return candidates;
        }

        boolean knowsHall(UUID sessionHallId) {
            return hallById.containsKey(sessionHallId);
        }

        int dutyCount(UUID facultyId) {
            return duties.get(facultyId).size();
        }

        Assigned holder(Position p) {
            return assigned.get(p.index());
        }

        /** The position {@code facultyId} holds in this hall and role, or null. */
        Position heldBy(UUID sessionHallId, Role role, UUID facultyId) {
            for (Position p : duties.getOrDefault(facultyId, List.of())) {
                if (p.hall().id().equals(sessionHallId) && p.role() == role) return p;
            }
            return null;
        }

        /**
         * A vacant position of this hall and role. When every seat is taken (or the hall no longer
         * needs anyone under current staffing), an extra seat is added so the rules can still be
         * applied to it.
         */
        Position vacantPosition(UUID sessionHallId, Role role) {
            for (Position p : positions) {
                if (p.hall().id().equals(sessionHallId) && p.role() == role && !assigned.containsKey(p.index())) return p;
            }
            SessionSlot s = sessionOfHall.get(sessionHallId);
            Position extra = new Position(positions.size(), groupOf.get(s.id()), s, hallById.get(sessionHallId), role);
            positions.add(extra);
            positionsByGroup.get(extra.group()).add(extra);
            return extra;
        }

        private List<Position> unfilledPositions() {
            return positions.stream().filter(p -> !assigned.containsKey(p.index())).toList();
        }

        /** Cheapest legal candidate for {@code p} other than {@code except}, or -1. */
        private int bestCandidate(Position p, UUID except) {
            int best = -1;
            long bestCost = Long.MAX_VALUE;
            for (int ci = 0; ci < candidates.size(); ci++) {
                Candidate c = candidates.get(ci);
                if (c.id().equals(except) || !reasons(c, p, false).isEmpty()) continue;
                long cost = cost(c, ci, p, null);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = ci;
                }
            }
            return best;
        }

        private int eligibleCount(Position p) {
            int n = 0;
            for (Candidate c : candidates) if (reasons(c, p, false).isEmpty()) n++;
            return n;
        }

        /**
         * For each unfilled position: fill it directly if someone became free, or free a faculty
         * member blocked only by a cross-session limit by moving one of their other duties to
         * someone else (depth 1).
         */
        private void repair() {
            for (Position p : unfilledPositions()) {
                int direct = bestCandidate(p, null);
                if (direct >= 0) {
                    Candidate c = candidates.get(direct);
                    assign(c, p, new Explanation(eligibleCount(p), duties.get(c.id()).size(), List.of("filled in the repair pass")));
                    continue;
                }
                boolean fixed = false;
                for (int ci = 0; ci < candidates.size() && !fixed; ci++) {
                    Candidate c = candidates.get(ci);
                    List<Reason> rs = reasons(c, p, true);
                    if (rs.isEmpty() || !CROSS_SESSION.containsAll(rs)) continue;
                    List<Position> others = duties.get(c.id()).stream()
                            .filter(d -> d.group() != p.group())
                            .sorted(Comparator.comparingInt(Position::index))
                            .toList();
                    for (Position other : others) {
                        Assigned original = unassign(other);
                        if (reasons(c, p, false).isEmpty()) {
                            assign(c, p, new Explanation(0, duties.get(c.id()).size(), List.of()));
                            int replacement = bestCandidate(other, c.id());
                            if (replacement >= 0) {
                                Candidate r = candidates.get(replacement);
                                assigned.remove(p.index());
                                duties.get(c.id()).remove(p);
                                groupMembers.get(p.group()).remove(c.id());
                                assign(c, p, new Explanation(eligibleCount(p) + 1, duties.get(c.id()).size(),
                                        List.of("freed by moving their " + other.session().label() + " duty in "
                                                + other.hall().hallCode() + " to " + r.name())));
                                assign(r, other, new Explanation(eligibleCount(other) + 1, duties.get(r.id()).size(),
                                        List.of("took over from " + c.name() + " in the repair pass")));
                                fixed = true;
                                break;
                            }
                            unassign(p);
                        }
                        assign(original.candidate(), other, original.explanation());
                    }
                }
            }
        }

        /** Every assignment must still pass every hard rule against all the others. */
        private void validate() {
            List<Violation> found = violations();
            if (!found.isEmpty()) {
                Violation v = found.getFirst();
                throw new IllegalStateException("Allocation produced an invalid assignment (" + v.reasons().getFirst() + ") for "
                        + v.candidate().code() + " in " + v.position().hall().hallCode() + " on " + v.position().session().date());
            }
        }

        /** Each assignment checked against all the others, with every rule it breaks. */
        List<Violation> violations() {
            List<Violation> out = new ArrayList<>();
            for (Integer index : new TreeSet<>(assigned.keySet())) {
                Position p = positions.get(index);
                Assigned a = unassign(p);
                List<Reason> rs = reasons(a.candidate(), p, true);
                assign(a.candidate(), p, a.explanation());
                if (!rs.isEmpty()) out.add(new Violation(p, a.candidate(), List.copyOf(rs)));
            }
            return out;
        }

        // ---- explanations and stats ----

        private List<Unfilled> explainUnfilled() {
            Map<String, List<Position>> byHallRole = new LinkedHashMap<>();
            for (Position p : unfilledPositions()) {
                byHallRole.computeIfAbsent(p.hall().id() + "/" + p.role(), k -> new ArrayList<>()).add(p);
            }
            List<Unfilled> out = new ArrayList<>();
            for (List<Position> group : byHallRole.values()) out.add(explain(group.getFirst(), group.size()));
            return out;
        }

        /** Why nobody can take {@code p}: first-failing reason counts and suggestions. */
        Unfilled explain(Position p, int missing) {
            Map<Reason, Integer> eliminated = new EnumMap<>(Reason.class);
            Map<Reason, Integer> onlyReason = new EnumMap<>(Reason.class);
            for (Candidate c : candidates) {
                List<Reason> first = reasons(c, p, false);
                if (!first.isEmpty()) eliminated.merge(first.getFirst(), 1, Integer::sum);
                List<Reason> all = reasons(c, p, true);
                if (all.size() == 1 && all.getFirst().configurable()) onlyReason.merge(all.getFirst(), 1, Integer::sum);
            }
            List<String> suggestions = new ArrayList<>();
            onlyReason.entrySet().stream()
                    .sorted(Map.Entry.<Reason, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .forEach(e -> suggestions.add(e.getValue() + " faculty are blocked only because they "
                            + e.getKey().description() + "; relaxing that rule in the policy would make them eligible."));
            if (eliminated.getOrDefault(Reason.BUSY_IN_SESSION, 0) > 0 && onlyReason.isEmpty()) {
                suggestions.add("Everyone else who could take it is already on duty at this time; add faculty, "
                        + "or lower the invigilators needed.");
            }
            suggestions.add("Reduce the invigilators required for " + p.hall().hallCode()
                    + " (manual override on the session page) or seat its students in another hall.");
            return new Unfilled(p.session().id(), p.hall().id(), p.role(), missing, candidates.size(),
                    eliminated, suggestions);
        }

        private Stats stats(long elapsedMillis) {
            int n = candidates.size();
            int min = n == 0 ? 0 : Integer.MAX_VALUE;
            int max = 0;
            double sum = 0;
            for (Candidate c : candidates) {
                int d = duties.get(c.id()).size();
                min = Math.min(min, d);
                max = Math.max(max, d);
                sum += d;
            }
            double mean = n == 0 ? 0 : sum / n;
            double var = 0;
            for (Candidate c : candidates) var += Math.pow(duties.get(c.id()).size() - mean, 2);
            double std = n == 0 ? 0 : Math.sqrt(var / n);
            return new Stats(positions.size(), assigned.size(), n, min, max, round2(mean), round2(std), elapsedMillis);
        }

        private static double round2(double v) {
            return Math.round(v * 100) / 100.0;
        }
    }
}
