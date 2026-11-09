package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.identity.Role;
import com.invigilo.policy.PolicySettings;
import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A small exam day shared by the chart tests: two departments, halls H1 and H2, FN and AN sessions
 * (FN: H1 with CS301 needs 2, H2 needs 1; AN: H1 needs 2), faculty F1..F6 where F1 teaches CS301
 * and F2 is on leave all day.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
abstract class ChartScenario extends IntegrationTest {

    TestData.Registered college;
    String cse;
    String ece;
    String cs301;
    String ec201;
    String h1;
    String h2;
    String period;
    String fn;
    String an;
    Map<String, String> facultyIds = new HashMap<>();
    String base;

    @BeforeEach
    void setUp() {
        college = data().registerCollege();
        send(PUT, "/api/v1/policy", Map.of("basedOnVersion", 0, "settings", PolicySettings.defaults()));
        cse = id(send(POST, "/api/v1/departments", Map.of("code", "CSE", "name", "CS", "active", true)));
        ece = id(send(POST, "/api/v1/departments", Map.of("code", "ECE", "name", "EC", "active", true)));
        cs301 = id(send(POST, "/api/v1/subjects", Map.of("code", "CS301", "name", "Algorithms", "departmentId", cse, "active", true)));
        ec201 = id(send(POST, "/api/v1/subjects", Map.of("code", "EC201", "name", "Signals", "departmentId", ece, "active", true)));
        h1 = id(send(POST, "/api/v1/halls", Map.of("code", "H1", "name", "Hall 1", "capacity", 60, "minInvigilators", 1, "lab", false, "active", true)));
        h2 = id(send(POST, "/api/v1/halls", Map.of("code", "H2", "name", "Hall 2", "capacity", 40, "minInvigilators", 1, "lab", false, "active", true)));
        period = id(send(POST, "/api/v1/exam-periods", Map.of("name", "Mar 2027", "startDate", "2027-03-01", "endDate", "2027-03-05")));
        base = "/api/v1/exam-periods/" + period + "/duty-chart";
        fn = id(send(POST, "/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2027-03-01", "label", "FN", "startTime", "09:30", "endTime", "12:30")));
        an = id(send(POST, "/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2027-03-01", "label", "AN", "startTime", "13:30", "endTime", "16:30")));
        for (String code : List.of("F1", "F2", "F3", "F4", "F5", "F6")) {
            Map<String, Object> body = new HashMap<>(Map.of("employeeCode", code, "fullName", "Faculty " + code,
                    "departmentId", code.equals("F1") ? cse : ece, "employmentType", "REGULAR", "chiefEligible", true,
                    "dutyWeight", 1, "active", true));
            body.put("email", code.toLowerCase() + "." + TestData.unique().toLowerCase() + "@college.example.test");
            if (code.equals("F1")) body.put("subjectIds", List.of(cs301)); // never in the hall writing CS301
            facultyIds.put(code, id(send(POST, "/api/v1/faculty", body)));
        }
        // F2 is on approved leave for the whole day.
        send(POST, "/api/v1/availability", Map.of("facultyId", facultyIds.get("F2"), "fromDate", "2027-03-01", "toDate", "2027-03-01", "type", "LEAVE"));
        seat(fn, h1, exam(fn, cs301), 45);   // 2 invigilators
        seat(fn, h2, exam(fn, ec201), 20);   // 1
        seat(an, h1, exam(an, ec201), 50);   // 2
    }

    // ---- helpers ----

    ResponseEntity<Map> send(HttpMethod method, String path, Object body) {
        return data().send(method, path, college.accessToken(), body, Map.class);
    }

    static String id(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        return (String) r.getBody().get("id");
    }

    String exam(String sessionId, String subjectId) {
        var detail = send(POST, "/api/v1/exam-sessions/" + sessionId + "/exams", Map.of("subjectId", subjectId, "type", "THEORY")).getBody();
        return ((List<Map<String, Object>>) detail.get("exams")).stream()
                .filter(e -> subjectId.equals(((Map) e.get("subject")).get("id"))).map(e -> (String) e.get("id")).findFirst().orElseThrow();
    }

    void seat(String sessionId, String hallId, String examId, int students) {
        var r = send(POST, "/api/v1/exam-sessions/" + sessionId + "/halls",
                Map.of("hallId", hallId, "seating", List.of(Map.of("examId", examId, "studentCount", students))));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
    }

    /** Signs in as faculty member {@code code} through the invitation flow; returns their access token. */
    String loginAs(String code) {
        String token = (String) send(POST, "/api/v1/faculty/" + facultyIds.get(code) + "/access/invitation", Map.of("roles", List.of()))
                .getBody().get("token");
        var accepted = http.postForEntity("/api/v1/auth/invitations/accept",
                TestData.json(Map.of("token", token, "password", "faculty-password")), Map.class);
        assertThat(accepted.getStatusCode()).as(String.valueOf(accepted.getBody())).isEqualTo(HttpStatus.OK);
        return (String) accepted.getBody().get("accessToken");
    }

    Map<String, Object> generate() {
        var r = send(POST, base + "/generate", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    Map<String, Object> chart() {
        return send(GET, base, null).getBody();
    }

    static Map<String, Object> hall(Map<String, Object> chart, String sessionLabel, String hallCode) {
        return ((List<Map<String, Object>>) chart.get("sessions")).stream()
                .filter(s -> sessionLabel.equals(s.get("label")))
                .flatMap(s -> ((List<Map<String, Object>>) s.get("halls")).stream())
                .filter(h -> hallCode.equals(h.get("hallCode")))
                .findFirst().orElseThrow();
    }

    static List<Map<String, Object>> duties(Map<String, Object> chart, String sessionLabel, String hallCode) {
        return (List<Map<String, Object>>) hall(chart, sessionLabel, hallCode).get("duties");
    }

    static Set<String> codesIn(Map<String, Object> chart, String sessionLabel) {
        Set<String> codes = new HashSet<>();
        for (String h : List.of("H1", "H2")) {
            ((List<Map<String, Object>>) chart.get("sessions")).stream().filter(s -> sessionLabel.equals(s.get("label")))
                    .flatMap(s -> ((List<Map<String, Object>>) s.get("halls")).stream()).filter(x -> h.equals(x.get("hallCode")))
                    .flatMap(x -> ((List<Map<String, Object>>) x.get("duties")).stream())
                    .forEach(d -> codes.add((String) d.get("facultyCode")));
        }
        return codes;
    }

    /** Someone allowed in the FN session who has no FN duty yet (not F2, on leave; not F1 for H1). */
    String freeInMorning(Map<String, Object> chart) {
        Set<String> busy = codesIn(chart, "FN");
        return List.of("F3", "F4", "F5", "F6").stream().filter(c -> !busy.contains(c)).findFirst().orElseThrow();
    }

    List<Map<String, Object>> history() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : data().send(GET, base + "/history", college.accessToken(), null, List.class).getBody()) {
            out.add((Map<String, Object>) entry);
        }
        return out;
    }

    static List<Object> field(List<Map<String, Object>> rows, String name) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : rows) out.add(row.get(name));
        return out;
    }

    Map<String, Object> validate(Map<String, Object> body) {
        var r = send(POST, base + "/assignments/validate", body);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static List<String> codes(Map<String, Object> validation, String list) {
        return ((List<Map<String, Object>>) validation.get(list)).stream().map(i -> (String) i.get("code")).toList();
    }
}
