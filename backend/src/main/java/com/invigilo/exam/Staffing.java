package com.invigilo.exam;

import com.invigilo.policy.PolicySettings;

/**
 * How many invigilators one session hall needs (docs/ALLOCATION_ENGINE.md §3). Pure function; the
 * allocation engine uses the same rule to create positions.
 *
 * @param invigilators ordinary invigilator positions
 * @param chief        chief-invigilator positions (0 or 1)
 * @param total        all positions = invigilators + chief
 * @param basis        plain-language explanation shown to admins
 */
public record Staffing(int invigilators, int chief, int total, String basis) {

    public static Staffing compute(PolicySettings policy, int hallMinimum, int students, boolean anyPractical,
                                   Integer override, String overrideReason) {
        if (students == 0 && override == null) {
            return new Staffing(0, 0, 0, "No students seated");
        }
        var staffing = policy.staffing();
        int ratio = anyPractical && staffing.practicalStudentsPerInvigilator() != null
                ? staffing.practicalStudentsPerInvigilator()
                : staffing.studentsPerInvigilator();
        int byStudents = (students + ratio - 1) / ratio;
        int policyMin = staffing.minInvigilatorsPerHall();
        int required;
        String basis;
        if (override != null) {
            required = override;
            basis = "Set manually to " + override + ": " + overrideReason;
        } else {
            required = Math.max(Math.max(policyMin, hallMinimum), byStudents);
            basis = "Largest of college minimum " + policyMin + ", hall minimum " + hallMinimum + " and "
                    + students + " students / " + ratio + (anyPractical && ratio != staffing.studentsPerInvigilator()
                            ? " (practical ratio)" : "") + " = " + byStudents;
        }
        boolean chiefPerHall = policy.faculty().chiefInvigilator() == PolicySettings.ChiefInvigilatorRule.PER_HALL;
        if (!chiefPerHall || required == 0) {
            return new Staffing(required, 0, required, basis);
        }
        if (policy.faculty().chiefCountsTowardsStaffing()) {
            return new Staffing(required - 1, 1, required, basis + "; one of them is the chief invigilator");
        }
        return new Staffing(required, 1, required + 1, basis + "; plus one chief invigilator");
    }
}
