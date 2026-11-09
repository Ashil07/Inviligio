package com.invigilo.allocation.engine;

import static com.invigilo.allocation.engine.AllocationEngineTest.CSE;
import static com.invigilo.allocation.engine.AllocationEngineTest.DAY;
import static com.invigilo.allocation.engine.AllocationEngineTest.ECE;
import static com.invigilo.allocation.engine.AllocationEngineTest.faculty;
import static com.invigilo.allocation.engine.AllocationEngineTest.hall;
import static com.invigilo.allocation.engine.AllocationEngineTest.id;
import static com.invigilo.allocation.engine.AllocationEngineTest.policy;
import static com.invigilo.allocation.engine.AllocationEngineTest.session;
import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.allocation.engine.AllocationProblem.Candidate;
import com.invigilo.allocation.engine.AllocationProblem.HallSlot;
import com.invigilo.allocation.engine.AllocationProblem.SessionSlot;
import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.allocation.engine.ChartChecker.Alternative;
import com.invigilo.allocation.engine.ChartChecker.Placement;
import com.invigilo.policy.PolicySettings.ChiefInvigilatorRule;
import com.invigilo.policy.PolicySettings.DepartmentRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChartCheckerTest {

    static final UUID ALGORITHMS = id("subject-cs301");

    // One day: FN with halls A (CS301, CSE) and B, AN with hall C.
    final HallSlot a = hall("A", 1, 0, Set.of(ALGORITHMS), Set.of(CSE));
    final HallSlot b = hall("B", 1);
    final HallSlot c = hall("C", 1);
    final SessionSlot fn = session("FN", DAY, "09:30", "12:30", a, b);
    final SessionSlot an = session("AN", DAY, "13:30", "16:30", c);

    final Candidate f1 = faculty("F1", ECE, Set.of(), Set.of(), true);
    final Candidate f2 = faculty("F2", ECE, Set.of(), Set.of(), true);
    final Candidate f3 = faculty("F3", ECE, Set.of(), Set.of(), true);
    final Candidate teacher = faculty("TEACH", CSE, Set.of(ALGORITHMS), Set.of(), true);
    final Candidate onLeave = faculty("LEAVE", ECE, Set.of(), Set.of(fn.id()), true);

    ChartChecker checker(int maxPerDay, List<Placement> placements) {
        var problem = new AllocationProblem(policy(maxPerDay, ChiefInvigilatorRule.NONE, DepartmentRule.ALLOW),
                List.of(fn, an), List.of(f1, f2, f3, teacher, onLeave));
        return new ChartChecker(problem, placements);
    }

    static Placement at(HallSlot hall, Candidate who) {
        return new Placement(hall.id(), who.id(), Role.INVIGILATOR);
    }

    @Test
    void blocksExactlyWhatGenerationWouldNeverDo() {
        var chart = checker(2, List.of(at(a, f1)));

        assertThat(chart.check(b.id(), Role.INVIGILATOR, f1.id(), null).blocking()).containsExactly(Reason.BUSY_IN_SESSION);
        assertThat(chart.check(b.id(), Role.INVIGILATOR, onLeave.id(), null).blocking()).containsExactly(Reason.UNAVAILABLE);
        assertThat(chart.check(a.id(), Role.INVIGILATOR, teacher.id(), null).blocking()).contains(Reason.OWN_SUBJECT);
        assertThat(chart.check(a.id(), Role.CHIEF, onLeave.id(), null).blocking())
                .containsExactly(Reason.UNAVAILABLE);
        assertThat(chart.check(b.id(), Role.INVIGILATOR, f2.id(), null).allowed()).isTrue();
    }

    @Test
    void replacingJudgesTheSeatAsIfThePersonHadLeftIt() {
        var placements = List.of(at(a, f1), at(c, f2));

        // F1 may also take the afternoon with two duties a day allowed, but not with one.
        assertThat(checker(2, placements).check(c.id(), Role.INVIGILATOR, f1.id(), at(c, f2)).allowed()).isTrue();
        assertThat(checker(1, placements).check(c.id(), Role.INVIGILATOR, f1.id(), at(c, f2)).blocking())
                .containsExactly(Reason.MAX_PER_DAY);

        // Moving F1 within their own session is fine because their old seat is freed first.
        var chart = checker(2, placements);
        assertThat(chart.check(a.id(), Role.INVIGILATOR, f3.id(), at(a, f1)).allowed()).isTrue();
        // Checks never change the chart.
        assertThat(chart.duties(f1.id())).isEqualTo(1);
        assertThat(chart.duties(f2.id())).isEqualTo(1);
        assertThat(chart.violations()).isEmpty();
    }

    @Test
    void alternativesAreLegalAndCheapestFirst() {
        // F1 already works in the morning; LEAVE is away; TEACH may not sit in A (own subject).
        var chart = checker(2, List.of(at(b, f1)));

        List<Alternative> options = chart.alternatives(a.id(), Role.INVIGILATOR, null, 10);

        assertThat(options).extracting(Alternative::facultyId).containsExactly(f2.id(), f3.id());
        assertThat(chart.alternatives(a.id(), Role.INVIGILATOR, null, 1)).hasSize(1);
        // When replacing, the person being replaced is never offered, and the freed seat counts.
        assertThat(chart.alternatives(b.id(), Role.INVIGILATOR, at(b, f1), 10)).extracting(Alternative::facultyId)
                .containsExactly(f2.id(), f3.id(), teacher.id());
    }

    @Test
    void findsDutiesThatNoLongerFitAndSkipsUnknownPeople() {
        var gone = new Placement(b.id(), id("f-INACTIVE"), Role.INVIGILATOR);
        var chart = checker(2, List.of(at(a, onLeave), at(c, f2), gone));

        assertThat(chart.violations()).singleElement().satisfies(v -> {
            assertThat(v.placement()).isEqualTo(at(a, onLeave));
            assertThat(v.reasons()).containsExactly(Reason.UNAVAILABLE);
        });
        assertThat(chart.skipped()).containsExactly(gone);
        assertThat(chart.isCandidate(id("f-INACTIVE"))).isFalse();
    }

    @Test
    void warnsAboutSoftCostsAndAnUnevenWorkload() {
        // Own department set to AVOID: allowed, with a warning.
        var avoid = new AllocationProblem(policy(2, ChiefInvigilatorRule.NONE, DepartmentRule.AVOID),
                List.of(fn, an), List.of(f1, f2, f3, teacher, onLeave));
        var check = new ChartChecker(avoid, List.of()).check(b.id(), Role.INVIGILATOR, teacher.id(), null);
        assertThat(check.allowed()).isTrue();
        assertThat(check.warnings()).isEmpty();
        var hallOfCse = hall("D", 1, 0, Set.of(), Set.of(CSE));
        var withD = new AllocationProblem(avoid.policy(), List.of(session("EV", DAY.plusDays(1), "09:30", "12:30", hallOfCse)),
                List.of(teacher));
        assertThat(new ChartChecker(withD, List.of()).check(hallOfCse.id(), Role.INVIGILATOR, teacher.id(), null).warnings())
                .containsExactly("invigilates an exam of their own department");

        // One person with three duties among six faculty gets a workload warning for a fourth.
        List<SessionSlot> days = new ArrayList<>();
        for (int d = 0; d < 4; d++) days.add(session("D" + d, DAY.plusDays(d), "09:30", "12:30", hall("H@" + d, 1)));
        List<Candidate> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(faculty("W" + i));
        List<Placement> busy = new ArrayList<>();
        for (int d = 0; d < 3; d++) busy.add(new Placement(days.get(d).halls().getFirst().id(), six.getFirst().id(), Role.INVIGILATOR));
        var heavy = new ChartChecker(new AllocationProblem(policy(), days, six), busy);
        assertThat(heavy.check(days.get(3).halls().getFirst().id(), Role.INVIGILATOR, six.getFirst().id(), null).warnings())
                .anySatisfy(w -> assertThat(w).startsWith("would have 4 duties in this period; the average is 0.7"));
        assertThat(heavy.check(days.get(3).halls().getFirst().id(), Role.INVIGILATOR, six.get(1).id(), null).warnings())
                .isEmpty();
    }

    @Test
    void explainsAnOpenPositionLikeTheEngine() {
        // B is open; F1 works in A at the same time and LEAVE is away.
        var chart = checker(2, List.of(at(a, f1)));

        var open = chart.explainOpen(b.id(), Role.INVIGILATOR, 1);

        assertThat(open.missing()).isEqualTo(1);
        assertThat(open.totalFaculty()).isEqualTo(5);
        assertThat(open.eliminated()).containsEntry(Reason.BUSY_IN_SESSION, 1).containsEntry(Reason.UNAVAILABLE, 1);
        assertThat(open.suggestions()).anyMatch(s -> s.contains("Reduce the invigilators required for B"));
    }
}
