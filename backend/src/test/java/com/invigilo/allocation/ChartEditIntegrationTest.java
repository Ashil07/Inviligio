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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Manual chart editing (M9): validate, replace, remove, add, history, issues, roles and isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ChartEditIntegrationTest extends IntegrationTest {

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

    // ---- tests ----

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
