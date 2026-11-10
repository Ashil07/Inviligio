package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The HOD dashboard (M12): own department only, published charts only, workload against the college. */
@SuppressWarnings({"unchecked", "rawtypes"})
class DepartmentDashboardIntegrationTest extends ChartScenario {

    ResponseEntity<Map> overview(String token, String query) {
        return data().send(GET, "/api/v1/department/overview" + query, token, null, Map.class);
    }

    Map<String, Object> ok(String token, String query) {
        var r = overview(token, query);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static List<Map<String, Object>> list(Map<String, Object> body, String name) {
        return (List<Map<String, Object>>) body.get(name);
    }

    @Test
    void headSeesTheirDepartmentInThePublishedChart() {
        String hod = loginAs("F3", List.of("HOD"));
        var draft = generate();

        // Before publishing: the department and its people, but no chart.
        Map<String, Object> before = ok(hod, "");
        assertThat((Map<String, Object>) before.get("department")).containsEntry("code", "ECE");
        assertThat(before.get("periodId")).isNull();
        assertThat(list(before, "periods")).isEmpty();
        assertThat(list(before, "duties")).isEmpty();
        assertThat(field(list(before, "faculty"), "code")).containsExactlyInAnyOrder("F2", "F3", "F4", "F5", "F6");

        publishCurrentDraft();
        Map<String, Object> after = ok(hod, "");

        assertThat(after).containsEntry("periodId", period).containsEntry("periodName", "Mar 2027").containsEntry("chartVersion", 1);
        assertThat(list(after, "periods")).hasSize(1);
        // Only ECE duties: everyone in the chart except F1 (CSE).
        long eceDuties = ((List<Map<String, Object>>) draft.get("sessions")).stream()
                .flatMap(s -> ((List<Map<String, Object>>) s.get("halls")).stream())
                .flatMap(h -> ((List<Map<String, Object>>) h.get("duties")).stream())
                .filter(d -> "ECE".equals(d.get("departmentCode"))).count();
        var duties = list(after, "duties");
        assertThat(duties).hasSize((int) eceDuties).allMatch(d -> !"F1".equals(d.get("facultyCode")));
        assertThat(after).containsEntry("departmentDuties", (int) eceDuties);
        assertThat(duties.getFirst()).containsEntry("date", "2027-03-01").containsEntry("sessionLabel", "FN");

        // Five positions, six active faculty: an even share is under one duty, so nobody is over or under.
        assertThat(((Number) after.get("collegeAverage")).doubleValue()).isEqualTo(0.8);
        Map<String, Object> f2 = list(after, "faculty").stream().filter(f -> "F2".equals(f.get("code"))).findFirst().orElseThrow();
        assertThat(f2).containsEntry("duties", 0).containsEntry("load", "BALANCED").containsEntry("active", true);
        int total = list(after, "faculty").stream().mapToInt(f -> ((Number) f.get("duties")).intValue()).sum();
        assertThat(total).isEqualTo((int) eceDuties);

        // The other head only sees CSE.
        Map<String, Object> cseView = ok(loginAs("F1", List.of("HOD")), "?periodId=" + period);
        assertThat(field(list(cseView, "faculty"), "code")).containsExactly("F1");
        assertThat(list(cseView, "duties")).allMatch(d -> "F1".equals(d.get("facultyCode")));
    }

    @Test
    void onlyHeadsOfDepartmentHaveADashboard() {
        generate();
        publishCurrentDraft();

        // Plain faculty and the exam cell are not heads of department.
        assertThat(overview(loginAs("F4"), "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(overview(college.accessToken(), "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(overview(null, "").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A period that does not exist or has no published chart.
        String hod = loginAs("F3", List.of("HOD"));
        var unknown = overview(hod, "?periodId=" + java.util.UUID.randomUUID());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody()).containsEntry("code", "PUBLISHED_CHART_NOT_FOUND");
    }

    @Test
    void anotherCollegesHeadCannotSeeThisCollegesChart() {
        generate();
        publishCurrentDraft();

        String otherHod = loginAtAnotherCollege(List.of("HOD"));

        assertThat(overview(otherHod, "?periodId=" + period).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> own = ok(otherHod, "");
        assertThat(list(own, "periods")).isEmpty();
        assertThat(field(list(own, "faculty"), "code")).containsExactly("M1");
    }
}
