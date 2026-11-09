package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.identity.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Manual chart editing (M9): validate, replace, remove, add, history, issues, roles and isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ChartEditIntegrationTest extends ChartScenario {


    @Test
    void validateReportsBlockingRulesWarningsAndLegalAlternatives() {
        var chart = generate();
        var duty = duties(chart, "FN", "H1").getFirst();
        String someoneInH2 = (String) duties(chart, "FN", "H2").getFirst().get("facultyId");

        var onLeave = validate(Map.of("replacesAssignmentId", duty.get("assignmentId"), "facultyId", facultyIds.get("F2")));
        assertThat(onLeave).containsEntry("allowed", false);
        assertThat(codes(onLeave, "blocking")).containsExactly("UNAVAILABLE");
        assertThat(((List<Map<String, Object>>) onLeave.get("blocking")).getFirst().get("message"))
                .isEqualTo("Faculty F2 is on approved leave or unavailable.");

        assertThat(codes(validate(Map.of("replacesAssignmentId", duty.get("assignmentId"), "facultyId", facultyIds.get("F1"))), "blocking"))
                .contains("OWN_SUBJECT");
        assertThat(codes(validate(Map.of("replacesAssignmentId", duty.get("assignmentId"), "facultyId", someoneInH2)), "blocking"))
                .contains("BUSY_IN_SESSION");
        assertThat(codes(validate(Map.of("replacesAssignmentId", duty.get("assignmentId"), "facultyId", duty.get("facultyId"))), "blocking"))
                .containsExactly("SAME_FACULTY");

        // Alternatives only: everyone offered is free in the morning and allowed in H1.
        var options = validate(Map.of("replacesAssignmentId", duty.get("assignmentId")));
        assertThat(options).containsEntry("allowed", true);
        List<String> offered = new ArrayList<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) options.get("alternatives")) offered.add((String) a.get("code"));
        assertThat(offered).isNotEmpty().doesNotContain("F1", "F2", (String) duty.get("facultyCode"))
                .doesNotContainAnyElementsOf(codesIn(chart, "FN"));

        // Filling a hall that has everyone it needs is reported, not silently allowed.
        var full = validate(Map.of("sessionHallId", hall(chart, "FN", "H2").get("sessionHallId"), "role", "INVIGILATOR",
                "facultyId", facultyIds.get(freeInMorning(chart))));
        assertThat(codes(full, "blocking")).containsExactly("POSITION_NOT_OPEN");
    }

    @Test
    void replaceSavesAllowedChangesAndRefusesRuleBreaksAndStaleEdits() {
        var chart = generate();
        var duty = duties(chart, "FN", "H1").getFirst();
        String assignment = (String) duty.get("assignmentId");

        var refused = send(PUT, base + "/assignments/" + assignment,
                Map.of("facultyId", facultyIds.get("F2"), "version", duty.get("version")));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody()).containsEntry("code", "HARD_RULE_VIOLATED");
        assertThat((String) refused.getBody().get("detail")).contains("Faculty F2 is on approved leave");
        assertThat(field(duties(chart(), "FN", "H1"), "facultyId")).contains(duty.get("facultyId"));

        String free = freeInMorning(chart);
        var saved = send(PUT, base + "/assignments/" + assignment,
                Map.of("facultyId", facultyIds.get(free), "version", duty.get("version"), "reason", "Swapped at request"));
        assertThat(saved.getStatusCode()).as(String.valueOf(saved.getBody())).isEqualTo(HttpStatus.OK);
        Map<String, Object> changed = null;
        Map<String, Object> savedChart = saved.getBody();
        for (Map<String, Object> d : duties(savedChart, "FN", "H1")) if (assignment.equals(d.get("assignmentId"))) changed = d;
        assertThat(changed).isNotNull();
        assertThat(changed).containsEntry("facultyCode", free).containsEntry("source", "MANUAL").containsEntry("explanation", null);
        assertThat((List<?>) saved.getBody().get("issues")).isEmpty();

        // The form that loaded the old version cannot overwrite the change.
        var stale = send(PUT, base + "/assignments/" + assignment,
                Map.of("facultyId", duty.get("facultyId"), "version", duty.get("version")));
        assertThat(stale.getBody()).containsEntry("code", "STALE_UPDATE");

        List<Map<String, Object>> history = history();
        assertThat(history.getFirst()).containsEntry("action", "DUTY_REPLACED").containsEntry("reason", "Swapped at request");
        assertThat((String) history.getFirst().get("summary"))
                .isEqualTo("Replaced " + duty.get("facultyName") + " (" + duty.get("facultyCode") + ") with Faculty " + free
                        + " (" + free + ") as invigilator in H1, Mon 1 Mar FN");
        assertThat(history.getFirst().get("actorName")).isNotNull();
        assertThat(field(history, "action")).containsExactly("DUTY_REPLACED", "DUTY_CHART_GENERATED");
    }

    @Test
    void removingOpensThePositionAndAddingFillsItAgain() {
        var chart = generate();
        var duty = duties(chart, "AN", "H1").getFirst();

        var removed = send(DELETE, base + "/assignments/" + duty.get("assignmentId") + "?version=" + duty.get("version")
                + "&reason=Called away", null);
        assertThat(removed.getStatusCode()).as(String.valueOf(removed.getBody())).isEqualTo(HttpStatus.OK);
        Map<String, Object> afterRemove = removed.getBody();
        assertThat(hall(afterRemove, "AN", "H1")).containsEntry("required", 2).containsEntry("missing", 1);
        assertThat((Map<String, Object>) afterRemove.get("stats")).containsEntry("positions", 5).containsEntry("filled", 4);
        var gap = ((List<Map<String, Object>>) afterRemove.get("unfilled")).getFirst();
        assertThat(gap).containsEntry("hallCode", "H1").containsEntry("sessionLabel", "AN").containsEntry("missing", 1);
        assertThat((Map<String, Integer>) ((Map) gap.get("reason")).get("eliminated")).containsEntry("UNAVAILABLE", 1);

        // A full hall cannot get an extra person, and the leave rule still holds.
        var full = send(POST, base + "/assignments", Map.of("sessionHallId", hall(afterRemove, "FN", "H2").get("sessionHallId"),
                "role", "INVIGILATOR", "facultyId", facultyIds.get("F3")));
        assertThat(full.getBody()).containsEntry("code", "POSITION_NOT_OPEN");
        String anH1 = (String) hall(afterRemove, "AN", "H1").get("sessionHallId");
        assertThat(send(POST, base + "/assignments", Map.of("sessionHallId", anH1, "role", "INVIGILATOR",
                "facultyId", facultyIds.get("F2"))).getBody()).containsEntry("code", "HARD_RULE_VIOLATED");

        Set<String> inAn = new HashSet<>();
        for (Map<String, Object> d : duties(afterRemove, "AN", "H1")) inAn.add((String) d.get("facultyCode"));
        String someone = List.of("F1", "F3", "F4", "F5", "F6").stream().filter(c -> !inAn.contains(c)).findFirst().orElseThrow();
        var added = send(POST, base + "/assignments", Map.of("sessionHallId", anH1, "role", "INVIGILATOR",
                "facultyId", facultyIds.get(someone)));
        assertThat(added.getStatusCode()).as(String.valueOf(added.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(hall(added.getBody(), "AN", "H1")).containsEntry("missing", 0);
        assertThat((List<?>) added.getBody().get("unfilled")).isEmpty();
        assertThat((Map<String, Object>) added.getBody().get("stats")).containsEntry("filled", 5);

        List<Map<String, Object>> history = history();
        assertThat(field(history, "action")).containsExactly("DUTY_ASSIGNED", "DUTY_REMOVED", "DUTY_CHART_GENERATED");
        assertThat(history.get(1)).containsEntry("reason", "Called away");
        assertThat((String) history.get(1).get("summary")).endsWith("; the position is open");
    }

    @Test
    void leaveApprovedAfterGenerationShowsUpAsAnIssue() {
        var chart = generate();
        var duty = duties(chart, "FN", "H1").getFirst();
        assertThat((List<?>) chart.get("issues")).isEmpty();

        send(POST, "/api/v1/availability", Map.of("facultyId", duty.get("facultyId"), "sessionId", fn, "type", "LEAVE"));

        List<Map<String, Object>> issues = (List<Map<String, Object>>) chart().get("issues");
        assertThat(issues).hasSize(1);
        Map<String, Object> issue = issues.getFirst();
        assertThat(issue).containsEntry("assignmentId", duty.get("assignmentId")).containsEntry("hallCode", "H1")
                .containsEntry("sessionLabel", "FN");
        assertThat((List<String>) issue.get("reasons")).containsExactly("UNAVAILABLE");
    }

    @Test
    void needsADraftAndOnlyThisCollegesExamCellCanEdit() {
        assertThat(send(POST, base + "/assignments/validate", Map.of()).getBody()).containsEntry("code", "NO_DRAFT");

        var chart = generate();
        var duty = duties(chart, "FN", "H1").getFirst();
        String hod = signInAsNewMember(college.collegeId(), Role.HOD, Role.FACULTY);
        var other = data().registerCollege();

        assertThat(data().send(POST, base + "/assignments/validate", hod, Map.of(), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(data().send(GET, base + "/history", hod, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(data().send(DELETE, base + "/assignments/" + duty.get("assignmentId") + "?version=0", hod, null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Another college sees nothing of this period, its duties or its faculty.
        assertThat(data().send(POST, base + "/assignments/validate", other.accessToken(), Map.of(), Map.class).getBody())
                .containsEntry("code", "EXAM_PERIOD_NOT_FOUND");
        assertThat(data().send(GET, base + "/history", other.accessToken(), null, Map.class).getBody())
                .containsEntry("code", "EXAM_PERIOD_NOT_FOUND");
        assertThat(data().send(PUT, base + "/assignments/" + duty.get("assignmentId"), other.accessToken(),
                Map.of("facultyId", facultyIds.get("F3"), "version", 0), Map.class).getBody()).containsEntry("code", "EXAM_PERIOD_NOT_FOUND");
        String strangerFaculty = (String) data().send(POST, "/api/v1/faculty", other.accessToken(), Map.of("employeeCode", "X1",
                "fullName", "Stranger", "departmentId", id(data().send(POST, "/api/v1/departments", other.accessToken(),
                        Map.of("code", "OTH", "name", "Other", "active", true), Map.class)),
                "employmentType", "REGULAR", "chiefEligible", true, "dutyWeight", 1, "active", true), Map.class).getBody().get("id");
        var foreign = send(PUT, base + "/assignments/" + duty.get("assignmentId"), Map.of("facultyId", strangerFaculty,
                "version", duty.get("version")));
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Nothing changed.
        assertThat(field(duties(chart(), "FN", "H1"), "assignmentId")).contains(duty.get("assignmentId"));
    }
}
