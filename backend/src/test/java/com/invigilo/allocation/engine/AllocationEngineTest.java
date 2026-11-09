package com.invigilo.allocation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.allocation.engine.AllocationProblem.Candidate;
import com.invigilo.allocation.engine.AllocationProblem.HallSlot;
import com.invigilo.allocation.engine.AllocationProblem.SessionSlot;
import com.invigilo.allocation.engine.AllocationResult.Assignment;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.policy.PolicySettings;
import com.invigilo.policy.PolicySettings.ChiefInvigilatorRule;
import com.invigilo.policy.PolicySettings.DepartmentRule;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AllocationEngineTest {

    static final LocalDate DAY = LocalDate.of(2027, 3, 1);
    static final UUID CSE = id("dept-cse");
    static final UUID ECE = id("dept-ece");

    final AllocationEngine engine = new AllocationEngine();

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    static SessionSlot session(String name, LocalDate date, String start, String end, HallSlot... halls) {
        return new SessionSlot(id(name), date, LocalTime.parse(start), LocalTime.parse(end), name, List.of(halls));
    }

    static HallSlot hall(String name, int invigilators, int chiefs, Set<UUID> subjects, Set<UUID> departments) {
        return new HallSlot(id("sh-" + name), id("hall-" + name.split("@")[0]), name.split("@")[0], subjects, departments,
                invigilators, chiefs);
    }

    static HallSlot hall(String name, int invigilators) {
        return hall(name, invigilators, 0, Set.of(), Set.of(ECE));
    }

    static Candidate faculty(String code) {
        return faculty(code, CSE, Set.of(), Set.of(), true);
    }

    static Candidate faculty(String code, UUID dept, Set<UUID> subjects, Set<UUID> unavailable, boolean chief) {
        return new Candidate(id("f-" + code), code, "Faculty " + code, dept, false, chief, 1.0, null, subjects, Set.of(),
                Set.of(), null, null, unavailable, 0);
    }

    static PolicySettings policy(int maxPerDay, ChiefInvigilatorRule chief, DepartmentRule ownDept) {
        var d = PolicySettings.defaults();
        var f = d.faculty();
        var w = d.workload();
        return new PolicySettings(
                new PolicySettings.FacultyRules(false, ownDept, f.visitingFacultyEligible(), false, false, chief, true),
                new PolicySettings.WorkloadRules(maxPerDay, w.consecutiveSessions(), 0, w.consecutiveDays(), null,
                        w.equalDistributionWeight(), w.historicalWorkloadPercent(), w.avoidRepeatHallWeight()),
                d.staffing(), d.availability(), d.swaps());
    }

    static PolicySettings policy() {
        return policy(2, ChiefInvigilatorRule.NONE, DepartmentRule.ALLOW);
    }

    static Map<UUID, Long> dutiesPerFaculty(AllocationResult r) {
        return r.assignments().stream().collect(Collectors.groupingBy(Assignment::facultyId, Collectors.counting()));
    }

    @Test
    void fillsEveryPositionAndSharesDutiesEqually() {
        List<SessionSlot> sessions = new ArrayList<>();
        for (int d = 0; d < 5; d++) {
            sessions.add(session("S" + d, DAY.plusDays(d), "09:30", "12:30", hall("A@" + d, 2), hall("B@" + d, 2)));
        }
        List<Candidate> faculty = new ArrayList<>();
        for (int i = 0; i < 10; i++) faculty.add(faculty("F" + i));

        var result = engine.allocate(new AllocationProblem(policy(), sessions, faculty));

        assertThat(result.unfilled()).isEmpty();
        assertThat(result.assignments()).hasSize(20);
        assertThat(result.stats().minDuties()).isEqualTo(2);
        assertThat(result.stats().maxDuties()).isEqualTo(2);
    }

    @Test
    void neverPlacesAnyoneInTwoOverlappingSessions() {
        var morning = session("FN", DAY, "09:00", "12:00", hall("A", 3));
        var practical = session("PR", DAY, "11:00", "14:00", hall("LAB", 3));
        List<Candidate> four = List.of(faculty("F1"), faculty("F2"), faculty("F3"), faculty("F4"));

        var result = engine.allocate(new AllocationProblem(policy(), List.of(morning, practical), four));

        assertThat(result.assignments()).hasSize(4);
        assertThat(dutiesPerFaculty(result).values()).allMatch(n -> n == 1);
        assertThat(result.unfilled()).hasSize(1);
        var gap = result.unfilled().getFirst();
        assertThat(gap.missing()).isEqualTo(2);
        assertThat(gap.eliminated()).containsEntry(Reason.BUSY_IN_SESSION, 4);
    }

    @Test
    void respectsUnavailabilityOwnSubjectsAndPersonalRestrictions() {
        UUID algorithms = id("subject-cs301");
        var s = session("FN", DAY, "09:30", "12:30", hall("A", 1, 0, Set.of(algorithms), Set.of(CSE)));
        var teaches = faculty("TEACH", CSE, Set.of(algorithms), Set.of(), true);
        var onLeave = faculty("LEAVE", CSE, Set.of(), Set.of(s.id()), true);
        var excluded = new Candidate(id("f-EXCL"), "EXCL", "Excluded", ECE, false, true, 1, null, Set.of(),
                Set.of(id("hall-A")), Set.of(), null, null, Set.of(), 0);
        var free = faculty("FREE", ECE, Set.of(), Set.of(), true);

        var result = engine.allocate(new AllocationProblem(policy(), List.of(s), List.of(teaches, onLeave, excluded, free)));

        assertThat(result.assignments()).extracting(Assignment::facultyId).containsExactly(free.id());
        assertThat(result.assignments().getFirst().explanation().eligibleCandidates()).isEqualTo(1);
    }

    @Test
    void forbiddenOwnDepartmentIsAHardRuleAndAvoidIsAPreference() {
        var s = session("FN", DAY, "09:30", "12:30", hall("A", 1, 0, Set.of(), Set.of(CSE)));
        var own = faculty("A-OWN", CSE, Set.of(), Set.of(), true);
        var other = faculty("B-OTHER", ECE, Set.of(), Set.of(), true);

        var forbid = engine.allocate(new AllocationProblem(policy(2, ChiefInvigilatorRule.NONE, DepartmentRule.FORBID), List.of(s), List.of(own)));
        var avoid = engine.allocate(new AllocationProblem(policy(2, ChiefInvigilatorRule.NONE, DepartmentRule.AVOID), List.of(s), List.of(own, other)));
        var avoidOnlyOwn = engine.allocate(new AllocationProblem(policy(2, ChiefInvigilatorRule.NONE, DepartmentRule.AVOID), List.of(s), List.of(own)));

        assertThat(forbid.assignments()).isEmpty();
        assertThat(forbid.unfilled().getFirst().eliminated()).containsEntry(Reason.OWN_DEPARTMENT, 1);
        assertThat(forbid.unfilled().getFirst().suggestions().getFirst()).contains("1 faculty").contains("relaxing");
        assertThat(avoid.assignments()).extracting(Assignment::facultyId).containsExactly(other.id());
        assertThat(avoidOnlyOwn.assignments()).extracting(Assignment::facultyId).containsExactly(own.id());
        assertThat(avoidOnlyOwn.assignments().getFirst().explanation().penalties()).anyMatch(p -> p.contains("own department"));
    }

    @Test
    void dailyLimitLeavesAPositionOpenAndSaysWhichRuleToRelax() {
        var fn = session("FN", DAY, "09:30", "12:30", hall("A", 1));
        var an = session("AN", DAY, "13:30", "16:30", hall("B", 1));

        var result = engine.allocate(new AllocationProblem(policy(1, ChiefInvigilatorRule.NONE, DepartmentRule.ALLOW),
                List.of(fn, an), List.of(faculty("ONLY"))));

        assertThat(result.assignments()).hasSize(1);
        var gap = result.unfilled().getFirst();
        assertThat(gap.eliminated()).containsEntry(Reason.MAX_PER_DAY, 1);
        assertThat(gap.suggestions().getFirst()).contains("maximum number of duties for the day");
    }

    @Test
    void chiefPositionsGoOnlyToChiefEligibleFaculty() {
        var s = session("FN", DAY, "09:30", "12:30", hall("A", 1, 1, Set.of(), Set.of(ECE)));
        var chief = faculty("CHIEF", CSE, Set.of(), Set.of(), true);
        var plain = faculty("PLAIN", CSE, Set.of(), Set.of(), false);

        var result = engine.allocate(new AllocationProblem(policy(2, ChiefInvigilatorRule.PER_HALL, DepartmentRule.ALLOW),
                List.of(s), List.of(plain, chief)));

        assertThat(result.assignments()).hasSize(2);
        assertThat(result.assignments()).filteredOn(a -> a.role() == Role.CHIEF).extracting(Assignment::facultyId)
                .containsExactly(chief.id());
    }

    @Test
    void repairMovesADutyToFreeTheOnlyPersonWhoCanFillALaterPosition() {
        // Both may do one duty in the period. The early position is far enough ahead of the late
        // one that the look-ahead cannot see it, so the greedy pass gives it to A; only A can take
        // the late position (B is on leave), so repair must hand the early duty to B.
        var early = session("EARLY", DAY, "09:30", "12:30", hall("X", 1));
        List<SessionSlot> sessions = new ArrayList<>(List.of(early));
        for (int i = 1; i <= AllocationEngine.LOOKAHEAD_GROUPS + 1; i++) sessions.add(session("EMPTY" + i, DAY.plusDays(i), "09:30", "12:30"));
        var late = session("LATE", DAY.plusDays(10), "09:30", "12:30", hall("Y", 1));
        sessions.add(late);
        var a = new Candidate(id("f-A"), "A", "Faculty A", CSE, false, true, 1, null, Set.of(), Set.of(), Set.of(), null, 1, Set.of(), 0);
        var b = new Candidate(id("f-B"), "B", "Faculty B", CSE, false, true, 1, null, Set.of(), Set.of(), Set.of(), null, 1, Set.of(late.id()), 0);

        var result = engine.allocate(new AllocationProblem(policy(), sessions, List.of(a, b)));

        assertThat(result.unfilled()).isEmpty();
        assertThat(result.assignments()).extracting(x -> x.sessionId() + ":" + x.facultyId())
                .containsExactlyInAnyOrder(early.id() + ":" + b.id(), late.id() + ":" + a.id());
        assertThat(result.assignments()).anyMatch(x -> x.explanation().penalties().stream().anyMatch(p -> p.contains("repair")));
    }

    @Test
    void sameInputGivesTheSameChartWhateverTheInputOrder() {
        var problem = largeProblem(40, 8, 6, 7);
        List<Candidate> shuffled = new ArrayList<>(problem.candidates());
        Collections.shuffle(shuffled, new Random(1));
        List<SessionSlot> reversed = new ArrayList<>(problem.sessions());
        Collections.reverse(reversed);
        var again = new AllocationProblem(problem.policy(), reversed, shuffled);

        var first = engine.allocate(problem);
        var second = engine.allocate(again);

        assertThat(second.assignments()).isEqualTo(first.assignments());
        assertThat(second.unfilled()).isEqualTo(first.unfilled());
        assertThat(ProblemFingerprint.of(again)).isEqualTo(ProblemFingerprint.of(problem));
    }

    @Test
    void handlesACollegeSizedPeriodQuicklyAndFairly() {
        // 300 faculty, 30 days x 2 sessions, 25 halls x 2 invigilators = 3000 positions.
        var problem = largeProblem(300, 30, 25, 42);

        var result = engine.allocate(problem);

        assertThat(result.stats().positions()).isEqualTo(3000);
        assertThat(result.unfilled()).isEmpty();
        assertThat(result.stats().elapsedMillis()).isLessThan(5000);
        assertThat(result.stats().maxDuties() - result.stats().minDuties()).isLessThanOrEqualTo(2);
        // No faculty member invigilates their own subject (default policy).
        Map<UUID, Candidate> byId = new HashMap<>();
        problem.candidates().forEach(c -> byId.put(c.id(), c));
        Map<UUID, HallSlot> halls = new HashMap<>();
        problem.sessions().forEach(s -> s.halls().forEach(h -> halls.put(h.id(), h)));
        assertThat(result.assignments()).allMatch(a -> Collections.disjoint(byId.get(a.facultyId()).subjectIds(),
                halls.get(a.sessionHallId()).subjectIds()));
    }

    /** Deterministic pseudo-random college: faculty teach 2 of 40 subjects; halls seat 2 subjects. */
    static AllocationProblem largeProblem(int facultyCount, int days, int hallsPerSession, long seed) {
        Random random = new Random(seed);
        List<UUID> subjects = new ArrayList<>();
        for (int i = 0; i < 40; i++) subjects.add(id("subject-" + i));
        List<UUID> departments = List.of(CSE, ECE, id("dept-me"), id("dept-ce"));
        List<SessionSlot> sessions = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            for (String slot : List.of("FN", "AN")) {
                List<HallSlot> halls = new ArrayList<>();
                for (int h = 0; h < hallsPerSession; h++) {
                    Set<UUID> seated = new HashSet<>(List.of(subjects.get(random.nextInt(40)), subjects.get(random.nextInt(40))));
                    halls.add(new HallSlot(id("sh-" + d + slot + h), id("hall-" + h), String.format("H%02d", h), seated,
                            Set.of(departments.get(random.nextInt(4))), 2, 0));
                }
                sessions.add(new SessionSlot(id("s-" + d + slot), DAY.plusDays(d),
                        LocalTime.parse(slot.equals("FN") ? "09:30" : "13:30"),
                        LocalTime.parse(slot.equals("FN") ? "12:30" : "16:30"), slot, halls));
            }
        }
        List<Candidate> faculty = new ArrayList<>();
        for (int i = 0; i < facultyCount; i++) {
            Set<UUID> teaches = new HashSet<>(List.of(subjects.get(random.nextInt(40)), subjects.get(random.nextInt(40))));
            Set<UUID> leave = new HashSet<>();
            if (random.nextInt(10) == 0) leave.add(sessions.get(random.nextInt(sessions.size())).id());
            faculty.add(new Candidate(id("f-" + i), String.format("F%03d", i), "Faculty " + i, departments.get(random.nextInt(4)),
                    false, random.nextBoolean(), 1.0, DAY.minusYears(random.nextInt(20)), teaches, Set.of(), Set.of(), null, null,
                    leave, 0));
        }
        return new AllocationProblem(PolicySettings.defaults(), sessions, faculty);
    }
}
