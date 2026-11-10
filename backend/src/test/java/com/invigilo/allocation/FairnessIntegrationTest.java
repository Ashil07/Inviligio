package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Fairness analytics (M15): published duties only, per-person figures, departments, history, gaps. */
@SuppressWarnings({"unchecked", "rawtypes"})
class FairnessIntegrationTest extends ChartScenario {

    Map<String, Object> fairness(String query) {
        var r = send(GET, "/api/v1/analytics/fairness" + query, null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static List<Map<String, Object>> list(Map<String, Object> body, String name) {
        return (List<Map<String, Object>>) body.get(name);
    }

    static int sum(List<Map<String, Object>> rows, String field) {
        return rows.stream().mapToInt(r -> ((Number) r.get(field)).intValue()).sum();
    }

    @Test
    void figuresComeFromThePublishedChartOnly() {
        generate();
        // A draft does not count.
        Map<String, Object> before = fairness("");
        assertThat(before.get("periodId")).isNull();
        assertThat(before.get("summary")).isNull();
        assertThat(list(before, "faculty")).hasSize(6).allSatisfy(f -> assertThat(f).containsEntry("duties", 0));
        assertThat(field(list(before, "departments"), "code")).containsExactlyInAnyOrder("CSE", "ECE");

        // Leave one position open, then publish.
        Map<String, Object> removed = duties(chart(), "AN", "H1").getFirst();
        var r = send(DELETE, base + "/assignments/" + removed.get("assignmentId") + "?version=" + removed.get("version"), null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        publishCurrentDraft();
        Map<String, Object> after = fairness("");

        assertThat(after).containsEntry("periodId", period).containsEntry("periodName", "Mar 2027");
        Map<String, Object> summary = (Map<String, Object>) after.get("summary");
        assertThat(summary).containsEntry("positions", 5).containsEntry("filled", 4).containsEntry("unfilled", 1)
                .containsEntry("activeFaculty", 6).containsEntry("duties", 4).containsEntry("mean", 0.67).containsEntry("min", 0);
        assertThat(((Number) summary.get("withoutDuty")).intValue()).isGreaterThanOrEqualTo(2); // F2 (on leave) and at least one more
        assertThat(sum(list(after, "faculty"), "duties")).isEqualTo(4);
        assertThat(sum(list(after, "faculty"), "allPeriods")).isEqualTo(4);
        assertThat(list(after, "faculty").getFirst()).containsKey("load").containsKey("fairShare");

        List<Map<String, Object>> departments = list(after, "departments");
        assertThat(sum(departments, "faculty")).isEqualTo(6);
        assertThat(sum(departments, "duties")).isEqualTo(4);

        assertThat(list(after, "unfilled")).singleElement().satisfies(u -> assertThat(u)
                .containsEntry("date", "2027-03-01").containsEntry("sessionLabel", "AN").containsEntry("hallCode", "H1")
                .containsEntry("missing", 1).containsKey("eliminated"));
        assertThat(list(after, "history")).singleElement().satisfies(h -> assertThat(h)
                .containsEntry("positions", 5).containsEntry("filled", 4).containsEntry("mean", 0.67));
    }

    @Test
    void historyCoversEveryPublishedPeriodOldestFirst() {
        generate();
        publishCurrentDraft();
        String april = id(send(POST, "/api/v1/exam-periods", Map.of("name", "Apr 2027", "startDate", "2027-04-05", "endDate", "2027-04-09")));
        String session = id(send(POST, "/api/v1/exam-periods/" + april + "/sessions",
                Map.of("date", "2027-04-05", "label", "FN", "startTime", "09:30", "endTime", "12:30")));
        seat(session, h2, exam(session, ec201), 20);
        String aprilBase = "/api/v1/exam-periods/" + april + "/duty-chart";
        assertThat(send(POST, aprilBase + "/generate", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        long version = ((Number) send(GET, aprilBase, null).getBody().get("version")).longValue();
        assertThat(send(POST, aprilBase + "/publish", Map.of("version", version)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The latest period is shown by default; the history lists both, oldest first.
        Map<String, Object> latest = fairness("");
        assertThat(latest).containsEntry("periodName", "Apr 2027");
        assertThat(field(list(latest, "periods"), "name")).containsExactly("Apr 2027", "Mar 2027");
        assertThat(field(list(latest, "history"), "name")).containsExactly("Mar 2027", "Apr 2027");
        assertThat(sum(list(latest, "faculty"), "duties")).isEqualTo(1);
        assertThat(sum(list(latest, "faculty"), "allPeriods")).isEqualTo(6);

        assertThat(fairness("?periodId=" + period)).containsEntry("periodName", "Mar 2027");
    }

    @Test
    void onlyTheExamCellOfThisCollegeSeesIt() {
        generate();
        publishCurrentDraft();

        assertThat(data().send(GET, "/api/v1/analytics/fairness", loginAs("F3"), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(data().send(GET, "/api/v1/analytics/fairness", loginAs("F4", List.of("HOD")), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var unknown = send(GET, "/api/v1/analytics/fairness?periodId=" + java.util.UUID.randomUUID(), null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String outsider = loginAtAnotherCollege(List.of("EXAM_CELL_ADMIN"));
        Map<String, Object> theirs = data().send(GET, "/api/v1/analytics/fairness", outsider, null, Map.class).getBody();
        assertThat(list(theirs, "periods")).isEmpty();
        assertThat(data().send(GET, "/api/v1/analytics/fairness?periodId=" + period, outsider, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
