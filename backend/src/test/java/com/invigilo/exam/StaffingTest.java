package com.invigilo.exam;

import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.policy.PolicySettings;
import com.invigilo.policy.PolicySettings.ChiefInvigilatorRule;
import org.junit.jupiter.api.Test;

class StaffingTest {

    static PolicySettings policy(int ratio, Integer practicalRatio, int policyMin, ChiefInvigilatorRule chief, boolean chiefCounts) {
        var d = PolicySettings.defaults();
        var f = d.faculty();
        return new PolicySettings(
                new PolicySettings.FacultyRules(f.allowOwnSubject(), f.ownDepartmentExams(), f.visitingFacultyEligible(),
                        f.seniorsGetFewerDuties(), f.preferSeniorsAsChief(), chief, chiefCounts),
                d.workload(),
                new PolicySettings.StaffingRules(ratio, practicalRatio, policyMin),
                d.availability(), d.swaps());
    }

    @Test
    void studentsDivideByRatioRoundedUp() {
        var s = Staffing.compute(policy(30, null, 1, ChiefInvigilatorRule.NONE, true), 1, 61, false, null, null);
        assertThat(s.total()).isEqualTo(3);
        assertThat(s.invigilators()).isEqualTo(3);
        assertThat(s.chief()).isZero();
    }

    @Test
    void minimumsWinOverSmallClasses() {
        assertThat(Staffing.compute(policy(30, null, 2, ChiefInvigilatorRule.NONE, true), 1, 5, false, null, null).total()).isEqualTo(2);
        assertThat(Staffing.compute(policy(30, null, 1, ChiefInvigilatorRule.NONE, true), 3, 5, false, null, null).total()).isEqualTo(3);
    }

    @Test
    void practicalRatioAppliesWhenAnyPracticalIsSeated() {
        var p = policy(30, 15, 1, ChiefInvigilatorRule.NONE, true);
        assertThat(Staffing.compute(p, 1, 30, true, null, null).total()).isEqualTo(2);
        assertThat(Staffing.compute(p, 1, 30, false, null, null).total()).isEqualTo(1);
        // Without a practical ratio the theory ratio is used.
        assertThat(Staffing.compute(policy(30, null, 1, ChiefInvigilatorRule.NONE, true), 1, 30, true, null, null).total()).isEqualTo(1);
    }

    @Test
    void chiefEitherCountsTowardsOrComesOnTop() {
        var counts = Staffing.compute(policy(30, null, 1, ChiefInvigilatorRule.PER_HALL, true), 1, 60, false, null, null);
        var extra = Staffing.compute(policy(30, null, 1, ChiefInvigilatorRule.PER_HALL, false), 1, 60, false, null, null);

        assertThat(counts).extracting(Staffing::invigilators, Staffing::chief, Staffing::total).containsExactly(1, 1, 2);
        assertThat(extra).extracting(Staffing::invigilators, Staffing::chief, Staffing::total).containsExactly(2, 1, 3);
    }

    @Test
    void overrideReplacesTheComputationAndExplainsWhy() {
        var s = Staffing.compute(policy(30, null, 1, ChiefInvigilatorRule.NONE, true), 1, 90, false, 2, "Two-row seating");
        assertThat(s.total()).isEqualTo(2);
        assertThat(s.basis()).contains("Two-row seating");
    }

    @Test
    void emptyHallNeedsNobody() {
        var s = Staffing.compute(policy(30, null, 2, ChiefInvigilatorRule.PER_HALL, false), 2, 0, false, null, null);
        assertThat(s.total()).isZero();
    }
}
