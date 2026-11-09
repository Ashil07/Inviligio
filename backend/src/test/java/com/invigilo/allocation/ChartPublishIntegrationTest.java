package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.identity.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Publishing and versions (M10): publish, refusals, timetable freeze, new drafts, superseding, roles, isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ChartPublishIntegrationTest extends ChartScenario {

    static long version(Map<String, Object> chart) {
        return ((Number) chart.get("version")).longValue();
    }

    ResponseEntity<Map> publish(long version) {
        return send(POST, base + "/publish", Map.of("version", version));
    }

    Map<String, Object> published(long version) {
        var r = publish(version);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    List<Map<String, Object>> versions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : data().send(GET, base + "/versions", college.accessToken(), null, List.class).getBody()) {
            out.add((Map<String, Object>) v);
        }
        return out;
    }

    @Test
    void publishesTheReviewedDraftAndFreezesTheTimetable() {
        var draft = generate();
        // Before anything is published, the list has the draft alone and nobody as publisher.
        List<Map<String, Object>> before = versions();
        assertThat(before).hasSize(1);
        assertThat(before.getFirst()).containsEntry("chartVersion", 1).containsEntry("status", "DRAFT").containsEntry("publishedBy", null);

        Map<String, Object> chart = published(version(draft));

        assertThat(chart).containsEntry("status", "PUBLISHED").containsEntry("chartVersion", 1).containsEntry("publishedVersion", 1);
        assertThat(chart.get("publishedAt")).isNotNull();
        assertThat(chart.get("publishedBy")).isNotNull();
        assertThat(send(GET, "/api/v1/exam-periods/" + period, null).getBody()).containsEntry("status", "PUBLISHED");
        // The timetable cannot change under a published chart, and the published chart cannot be edited.
        assertThat(send(POST, "/api/v1/exam-periods/" + period + "/sessions",
                Map.of("date", "2027-03-02", "label", "FN", "startTime", "09:30", "endTime", "12:30")).getBody())
                .containsEntry("code", "PERIOD_LOCKED");
        assertThat(send(POST, base + "/assignments/validate", Map.of()).getBody()).containsEntry("code", "NO_DRAFT");
        assertThat(publish(version(chart)).getBody()).containsEntry("code", "NO_DRAFT");

        assertThat(history().getFirst()).containsEntry("action", "DUTY_CHART_PUBLISHED")
                .containsEntry("summary", "Published version 1: 5 of 5 positions filled");
    }

    @Test
    void refusesRuleBreaksAndPublishesOnlyWhatWasReviewed() {
        var draft = generate();
        var duty = duties(draft, "FN", "H1").getFirst();
        // Leave approved after generation: the draft now breaks a strict rule.
        send(POST, "/api/v1/availability", Map.of("facultyId", duty.get("facultyId"), "sessionId", fn, "type", "LEAVE"));

        var refused = publish(version(draft));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("code", "CHART_HAS_ERRORS");
        assertThat((String) refused.getBody().get("detail")).startsWith("1 duty breaks a strict rule");

        // Fixing it is an edit, so the version the admin reviewed is now out of date.
        Map<String, Object> fixed = send(PUT, base + "/assignments/" + duty.get("assignmentId"),
                Map.of("facultyId", facultyIds.get(freeInMorning(draft)), "version", duty.get("version"))).getBody();
        assertThat(version(fixed)).isGreaterThan(version(draft));
        assertThat((List<?>) fixed.get("issues")).isEmpty();
        assertThat(publish(version(draft)).getBody()).containsEntry("code", "STALE_UPDATE");

        assertThat(published(version(fixed))).containsEntry("status", "PUBLISHED");
    }

    @Test
    void aNewVersionReplacesThePublishedOneOnlyWhenItIsPublished() {
        published(version(generate()));

        Map<String, Object> copy = send(POST, base + "/draft", null).getBody();
        assertThat(copy).containsEntry("status", "DRAFT").containsEntry("chartVersion", 2).containsEntry("publishedVersion", 1);
        assertThat(field(duties(copy, "AN", "H1"), "facultyId"))
                .containsExactlyInAnyOrderElementsOf(field(duties(send(GET, base + "/versions/1", null).getBody(), "AN", "H1"), "facultyId"));
        assertThat(send(POST, base + "/draft", null).getBody()).containsEntry("code", "DRAFT_EXISTS");
        assertThat(chart()).containsEntry("chartVersion", 2);
        assertThat(history().getFirst()).containsEntry("summary", "Started draft version 2 from published version 1");

        // Changing the draft leaves the published version exactly as it was.
        var removedDuty = duties(copy, "AN", "H1").getFirst();
        Map<String, Object> edited = send(DELETE, base + "/assignments/" + removedDuty.get("assignmentId") + "?version="
                + removedDuty.get("version"), null).getBody();
        Map<String, Object> v1 = send(GET, base + "/versions/1", null).getBody();
        assertThat(v1).containsEntry("status", "PUBLISHED");
        assertThat(duties(v1, "AN", "H1")).hasSize(2);

        Map<String, Object> v2 = published(version(edited));
        assertThat(v2).containsEntry("chartVersion", 2).containsEntry("publishedVersion", 2);
        var list = versions();
        assertThat(field(list, "chartVersion")).containsExactly(2, 1);
        assertThat(field(list, "status")).containsExactly("PUBLISHED", "SUPERSEDED");
        assertThat(list.get(0)).containsEntry("positions", 5).containsEntry("filled", 4);
        assertThat(list.get(1)).containsEntry("positions", 5).containsEntry("filled", 5);
        assertThat((String) history().getFirst().get("summary")).isEqualTo("Published version 2, replacing version 1: 4 of 5 positions filled");

        // A fresh draft can still be generated; the published version stays in force.
        assertThat(generate()).containsEntry("chartVersion", 3).containsEntry("status", "DRAFT").containsEntry("publishedVersion", 2);
    }

    @Test
    void onlyThisCollegesExamCellCanPublishOrSeeVersions() {
        var draft = generate();
        String hod = signInAsNewMember(college.collegeId(), Role.HOD, Role.FACULTY);
        var other = data().registerCollege();

        assertThat(data().send(POST, base + "/publish", hod, Map.of("version", version(draft)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(data().send(POST, base + "/draft", hod, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(data().send(GET, base + "/versions", hod, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(data().send(POST, base + "/publish", other.accessToken(), Map.of("version", version(draft)), Map.class).getBody())
                .containsEntry("code", "EXAM_PERIOD_NOT_FOUND");
        assertThat(data().send(GET, base + "/versions/1", other.accessToken(), null, Map.class).getBody())
                .containsEntry("code", "EXAM_PERIOD_NOT_FOUND");
        assertThat(send(GET, base + "/versions/9", null).getBody()).containsEntry("code", "CHART_VERSION_NOT_FOUND");
        assertThat(chart()).containsEntry("status", "DRAFT");
    }
}
