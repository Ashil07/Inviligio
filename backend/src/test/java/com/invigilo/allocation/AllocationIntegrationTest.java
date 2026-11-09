package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
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

@SuppressWarnings({"unchecked", "rawtypes"})
class AllocationIntegrationTest extends IntegrationTest {

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
        fn = id(send(POST, "/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2027-03-01", "label", "FN", "startTime", "09:30", "endTime", "12:30")));
        an = id(send(POST, "/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2027-03-01", "label", "AN", "startTime", "13:30", "endTime", "16:30")));
        for (String code : List.of("F1", "F2", "F3", "F4", "F5", "F6")) {
            Map<String, Object> body = new HashMap<>(Map.of("employeeCode", code, "fullName", "Faculty " + code,
                    "departmentId", code.equals("F1") ? cse : ece, "employmentType", "REGULAR", "chiefEligible", true,
                    "dutyWeight", 1, "active", true));
            // F1 teaches CS301, so must never be in the hall where CS301 is written.
            if (code.equals("F1")) body.put("subjectIds", List.of(cs301));
            facultyIds.put(code, id(send(POST, "/api/v1/faculty", body)));
        }
        // F2 is on approved leave for the whole day.
        send(POST, "/api/v1/availability", Map.of("facultyId", facultyIds.get("F2"), "fromDate", "2027-03-01", "toDate", "2027-03-01", "type", "LEAVE"));
    }

    ResponseEntity<Map> send(HttpMethod method, String path, Object body) {
        var r = data().send(method, path, college.accessToken(), body, Map.class);
        return r;
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

    void schedule() {
        seat(fn, h1, exam(fn, cs301), 45);   // 2 invigilators
        seat(fn, h2, exam(fn, ec201), 20);   // 1
        seat(an, h1, exam(an, ec201), 50);   // 2
    }

    Map<String, Object> generate() {
        var r = send(POST, "/api/v1/exam-periods/" + period + "/duty-chart/generate", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static List<Map<String, Object>> duties(Map<String, Object> chart, String sessionLabel, String hallCode) {
        return ((List<Map<String, Object>>) chart.get("sessions")).stream()
                .filter(s -> sessionLabel.equals(s.get("label")))
                .flatMap(s -> ((List<Map<String, Object>>) s.get("halls")).stream())
                .filter(h -> hallCode.equals(h.get("hallCode")))
                .flatMap(h -> ((List<Map<String, Object>>) h.get("duties")).stream())
                .toList();
    }

    @Test
    void generatesADraftThatRespectsEveryHardRule() {
        schedule();

        var chart = generate();

        assertThat(chart).containsEntry("chartVersion", 1).containsEntry("status", "DRAFT")
                .containsEntry("inputsChanged", false).containsEntry("policyChanged", false).containsEntry("policyVersion", 1);
        assertThat((Map<String, Object>) chart.get("stats")).containsEntry("positions", 5).containsEntry("filled", 5);
        assertThat((List<?>) chart.get("unfilled")).isEmpty();

        var fnH1 = duties(chart, "FN", "H1");
        var fnAll = new ArrayList<>(fnH1);
        fnAll.addAll(duties(chart, "FN", "H2"));
        var anAll = duties(chart, "AN", "H1");
        assertThat(fnH1).hasSize(2).noneMatch(d -> "F1".equals(d.get("facultyCode")));         // own subject
        assertThat(fnAll).noneMatch(d -> "F2".equals(d.get("facultyCode")));                    // on leave
        assertThat(anAll).noneMatch(d -> "F2".equals(d.get("facultyCode")));
        Set<Object> fnPeople = new HashSet<>();
        fnAll.forEach(d -> assertThat(fnPeople.add(d.get("facultyId"))).as("double-booked in FN").isTrue());
        assertThat((Map<String, Object>) fnH1.getFirst().get("explanation")).containsKey("eligibleCandidates");

        var workload = (List<Map<String, Object>>) chart.get("workload");
        assertThat(workload).hasSize(6);
        assertThat(workload.stream().mapToInt(w -> (Integer) w.get("duties")).sum()).isEqualTo(5);
        assertThat(send(HttpMethod.GET, "/api/v1/exam-periods/" + period, null).getBody()).containsEntry("status", "ALLOCATING");
    }

    @Test
    void regeneratingReplacesTheDraftAndChangesAreDetected() {
        schedule();
        generate();
        var again = generate();
        assertThat(again).containsEntry("chartVersion", 2).containsEntry("inputsChanged", false);

        // A schedule change makes the draft stale...
        var sessionDetail = send(HttpMethod.GET, "/api/v1/exam-sessions/" + an, null).getBody();
        var hall = ((List<Map<String, Object>>) sessionDetail.get("halls")).getFirst();
        var examId = ((List<Map<String, Object>>) sessionDetail.get("exams")).getFirst().get("id");
        send(PUT, "/api/v1/session-halls/" + hall.get("id"), Map.of("seating", List.of(Map.of("examId", examId, "studentCount", 20)),
                "version", hall.get("version")));
        assertThat(send(HttpMethod.GET, "/api/v1/exam-periods/" + period + "/duty-chart", null).getBody())
                .containsEntry("inputsChanged", true);

        // ...and so does a new policy version.
        var changed = PolicySettings.defaults();
        var w = changed.workload();
        changed = new PolicySettings(changed.faculty(), new PolicySettings.WorkloadRules(1, w.consecutiveSessions(), w.minRestMinutes(),
                w.consecutiveDays(), w.maxDutiesPerPeriod(), w.equalDistributionWeight(), w.historicalWorkloadPercent(),
                w.avoidRepeatHallWeight()), changed.staffing(), changed.availability(), changed.swaps());
        send(PUT, "/api/v1/policy", Map.of("basedOnVersion", 1, "settings", changed));
        assertThat(send(HttpMethod.GET, "/api/v1/exam-periods/" + period + "/duty-chart", null).getBody())
                .containsEntry("policyChanged", true);
    }

    @Test
    void positionsThatCannotBeFilledAreExplained() {
        schedule();
        // Leave for everyone except F1 and F3 on the AN session: AN H1 needs 2, F1 and F3 remain.
        for (String code : List.of("F4", "F5", "F6")) {
            send(POST, "/api/v1/availability", Map.of("facultyId", facultyIds.get(code), "sessionId", an, "type", "UNAVAILABLE"));
        }
        // Allow only one duty per day: F1 and F3 can then only do AN if they were not used in FN.
        var p = PolicySettings.defaults();
        var w = p.workload();
        send(PUT, "/api/v1/policy", Map.of("basedOnVersion", 1, "settings", new PolicySettings(p.faculty(),
                new PolicySettings.WorkloadRules(1, w.consecutiveSessions(), 0, w.consecutiveDays(), null, w.equalDistributionWeight(),
                        w.historicalWorkloadPercent(), w.avoidRepeatHallWeight()), p.staffing(), p.availability(), p.swaps())));

        var chart = generate();

        // FN needs 3 (F1 cannot take H1), only F1, F3, F4, F5, F6 are free; AN needs 2 of F1/F3.
        var stats = (Map<String, Object>) chart.get("stats");
        assertThat(stats.get("positions")).isEqualTo(5);
        var gaps = (List<Map<String, Object>>) chart.get("unfilled");
        int filled = (Integer) stats.get("filled");
        assertThat(filled + gaps.stream().mapToInt(g -> (Integer) g.get("missing")).sum()).isEqualTo(5);
        assertThat(filled).isEqualTo(5); // the engine keeps F1 and F3 free for AN
    }

    @Test
    void reportsWhyWhenThereAreTooFewFaculty() {
        schedule();
        // Everyone but F1 on leave in the morning: FN needs 3, and F1 may not sit in H1 (own subject).
        for (String code : List.of("F3", "F4", "F5", "F6")) {
            send(POST, "/api/v1/availability", Map.of("facultyId", facultyIds.get(code), "sessionId", fn, "type", "LEAVE"));
        }

        var chart = generate();

        var gaps = (List<Map<String, Object>>) chart.get("unfilled");
        var h1Gap = gaps.stream().filter(g -> "H1".equals(g.get("hallCode")) && "FN".equals(g.get("sessionLabel"))).findFirst().orElseThrow();
        assertThat(h1Gap.get("missing")).isEqualTo(2);
        var reason = (Map<String, Object>) h1Gap.get("reason");
        assertThat((Map<String, Integer>) reason.get("eliminated")).containsEntry("UNAVAILABLE", 5).containsEntry("OWN_SUBJECT", 1);
        // F1 is also busy in H2, so own-subject is not their only obstacle: no "relax the rule" suggestion.
        assertThat((List<String>) reason.get("suggestions")).noneMatch(s -> s.contains("relaxing"))
                .anyMatch(s -> s.contains("Reduce the invigilators required for H1"));
    }

    @Test
    void refusesWhenNothingIsScheduledAndGuardsData() {
        var nothing = send(POST, "/api/v1/exam-periods/" + period + "/duty-chart/generate", null);
        assertThat(nothing.getBody()).containsEntry("code", "NOTHING_TO_ALLOCATE");
        assertThat(send(HttpMethod.GET, "/api/v1/exam-periods/" + period + "/duty-chart", null).getBody()).containsEntry("code", "NO_CHART");

        schedule();
        var chart = generate();
        String someone = (String) duties(chart, "AN", "H1").getFirst().get("facultyId");
        assertThat(send(DELETE, "/api/v1/faculty/" + someone, null).getBody()).containsEntry("code", "FACULTY_IN_USE");
    }

    @Test
    void onlyThisCollegesExamCellCanGenerateOrSeeCharts() {
        schedule();
        generate();
        String hod = signInAsNewMember(college.collegeId(), Role.HOD, Role.FACULTY);
        var other = data().registerCollege();

        assertThat(data().send(POST, "/api/v1/exam-periods/" + period + "/duty-chart/generate", hod, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(data().get("/api/v1/exam-periods/" + period + "/duty-chart", other.accessToken(), Map.class).getBody())
                .containsEntry("code", "EXAM_PERIOD_NOT_FOUND");
        assertThat(data().send(POST, "/api/v1/exam-periods/" + period + "/duty-chart/generate", other.accessToken(), null, Map.class).getBody())
                .containsEntry("code", "EXAM_PERIOD_NOT_FOUND");
    }
}
