package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Emergency substitutes (M14): ranked candidates, the substitution itself, and what is refused. */
@SuppressWarnings({"unchecked", "rawtypes"})
class SubstituteIntegrationTest extends ChartScenario {

    /** The duty in FN H2 (one invigilator; any free faculty may take it). */
    Map<String, Object> morningH2() {
        return duties(chart(), "FN", "H2").getFirst();
    }

    String path(Map<String, Object> duty) {
        return base + "/assignments/" + duty.get("assignmentId") + "/substitute";
    }

    ResponseEntity<Map> substitute(Map<String, Object> duty, String code, String reason) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("facultyId", facultyIds.get(code), "version", duty.get("version")));
        if (reason != null) body.put("reason", reason);
        return send(POST, path(duty), body);
    }

    /** Faculty free that morning (not F2, who is on leave), in a stable order. */
    List<String> freeThatMorning() {
        Set<String> busy = codesIn(chart(), "FN");
        return List.of("F1", "F3", "F4", "F5", "F6").stream().filter(c -> !busy.contains(c)).toList();
    }

    @Test
    void theExamCellPicksARankedSubstituteAndTheDutyMoves() {
        generate();
        publishCurrentDraft();
        Map<String, Object> duty = morningH2();
        String absent = (String) duty.get("facultyCode");

        Map<String, Object> options = send(GET, path(duty), null).getBody();
        assertThat(options).containsEntry("allowed", true);
        List<String> offered = ((List<Map<String, Object>>) options.get("candidates")).stream().map(c -> (String) c.get("code")).toList();
        assertThat(offered).containsExactlyInAnyOrderElementsOf(freeThatMorning()).doesNotContain("F2", absent);
        Map<String, Object> excluded = (Map<String, Object>) options.get("excluded");
        assertThat(excluded).containsEntry("totalFaculty", 6);
        assertThat((Map<String, Object>) excluded.get("eliminated")).containsKey("UNAVAILABLE");

        String chosen = offered.getFirst();
        var r = substitute(duty, chosen, "Absent: fever");
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);

        Map<String, Object> moved = duties(chart(), "FN", "H2").getFirst();
        assertThat(moved).containsEntry("assignmentId", duty.get("assignmentId")).containsEntry("facultyCode", chosen)
                .containsEntry("source", "SUBSTITUTE");
        assertThat(history()).anySatisfy(h -> assertThat(h)
                .containsEntry("summary", "Substitute: Faculty " + chosen + " (" + chosen + ") took over from Faculty " + absent + " ("
                        + absent + ") as invigilator in H2, Mon 1 Mar FN")
                .containsEntry("reason", "Absent: fever"));
        var mine = (List<Map<String, Object>>) data().send(GET, "/api/v1/me/duties", loginAs(chosen), null, Map.class).getBody().get("upcoming");
        assertThat(mine).extracting(d -> d.get("assignmentId")).contains(duty.get("assignmentId"));
    }

    @Test
    void substitutesMustFollowTheRulesAndThePolicy() {
        generate();
        // Only the published chart takes substitutes.
        var noChart = send(GET, path(morningH2()), null);
        assertThat(noChart.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(noChart.getBody()).containsEntry("code", "NO_PUBLISHED_CHART");

        publishCurrentDraft();
        Map<String, Object> duty = morningH2();
        String free = freeThatMorning().getFirst();

        assertThat(substitute(duty, free, " ").getBody()).containsEntry("code", "REASON_REQUIRED");
        var onLeave = substitute(duty, "F2", "Absent");
        assertThat(onLeave.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(onLeave.getBody()).containsEntry("code", "HARD_RULE_VIOLATED");
        assertThat((String) onLeave.getBody().get("detail")).contains("Faculty F2 is on approved leave");
        var stale = send(POST, path(duty), Map.of("facultyId", facultyIds.get(free), "version", 99, "reason", "Absent"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Faculty and other colleges cannot do it.
        assertThat(data().send(GET, path(duty), loginAs(free), null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String outsider = loginAtAnotherCollege(List.of("EXAM_CELL_ADMIN"));
        assertThat(data().send(GET, path(duty), outsider, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // With direct reassignment switched off, the list says so and nothing can be changed.
        swapRules(true, true, false, false);
        assertThat(send(GET, path(duty), null).getBody()).containsEntry("allowed", false);
        assertThat(substitute(duty, free, "Absent").getBody()).containsEntry("code", "DIRECT_REASSIGNMENT_DISABLED");
        assertThat(duties(chart(), "FN", "H2").getFirst()).containsEntry("facultyCode", duty.get("facultyCode"));
    }

    @Test
    void anOpenSwapOfTheDutyIsInvalidatedAndADraftIsWarned() {
        generate();
        publishCurrentDraft();
        Map<String, Object> duty = morningH2();
        List<String> free = freeThatMorning();
        String holder = (String) duty.get("facultyCode");
        Map<String, Object> swap = data().send(POST, "/api/v1/me/swap-requests", loginAs(holder),
                Map.of("assignmentId", duty.get("assignmentId"), "targetFacultyId", facultyIds.get(free.get(0))), Map.class).getBody();
        assertThat(swap).containsEntry("status", "PENDING_RECIPIENT");
        assertThat(send(POST, base + "/draft", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(substitute(duty, free.get(1), "Absent: family emergency").getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> all = data().send(GET, "/api/v1/swap-requests", college.accessToken(), null, List.class).getBody();
        assertThat(all).singleElement().satisfies(s -> assertThat(s).containsEntry("status", "INVALIDATED")
                .containsEntry("invalidReason", "The examination cell gave this duty to Faculty " + free.get(1) + " as a substitute."));
        // The draft started before the substitution says it does not have it.
        assertThat(chart()).containsEntry("status", "DRAFT").containsEntry("changesNotInDraft", 1);
    }
}
