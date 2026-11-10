package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Remuneration (M17): rates with effective dates, pay from completed duties, attendance, report, access. */
@SuppressWarnings({"unchecked", "rawtypes"})
class RemunerationIntegrationTest extends ChartScenario {

    String past;
    String pastBase;

    /** A published exam day in January 2026 (already over): FN H1 needs 2, AN H2 needs 1. */
    void publishPastPeriod() {
        past = id(send(POST, "/api/v1/exam-periods", Map.of("name", "Jan 2026", "startDate", "2026-01-05", "endDate", "2026-01-09")));
        pastBase = "/api/v1/exam-periods/" + past + "/duty-chart";
        String morning = id(send(POST, "/api/v1/exam-periods/" + past + "/sessions",
                Map.of("date", "2026-01-05", "label", "FN", "startTime", "09:30", "endTime", "12:30")));
        String afternoon = id(send(POST, "/api/v1/exam-periods/" + past + "/sessions",
                Map.of("date", "2026-01-05", "label", "AN", "startTime", "13:30", "endTime", "16:30")));
        seat(morning, h1, exam(morning, cs301), 45);
        seat(afternoon, h2, exam(afternoon, ec201), 20);
        assertThat(send(POST, pastBase + "/generate", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        long version = ((Number) send(GET, pastBase, null).getBody().get("version")).longValue();
        assertThat(send(POST, pastBase + "/publish", Map.of("version", version)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    Map<String, Object> rate(String role, String amount, String from) {
        var r = send(POST, "/api/v1/remuneration/rates", Map.of("role", role, "amount", amount, "effectiveFrom", from));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    Map<String, Object> pay(String periodId) {
        var r = send(GET, "/api/v1/remuneration?periodId=" + periodId, null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static Map<String, Object> summary(Map<String, Object> pay) {
        return (Map<String, Object>) pay.get("summary");
    }

    List<Map<String, Object>> pastDuties() {
        return ((List<Map<String, Object>>) send(GET, pastBase, null).getBody().get("sessions")).stream()
                .flatMap(s -> ((List<Map<String, Object>>) s.get("halls")).stream())
                .flatMap(h -> ((List<Map<String, Object>>) h.get("duties")).stream()).toList();
    }

    ResponseEntity<Map> attendance(Map<String, Object> duty, boolean absent, Object version) {
        return send(PUT, pastBase + "/assignments/" + duty.get("assignmentId") + "/attendance",
                Map.of("absent", absent, "version", version, "reason", "Did not report"));
    }

    @Test
    void payFollowsTheRateOfTheDayAndAttendance() {
        publishPastPeriod();
        Map<String, Object> early = rate("INVIGILATOR", "500", "2025-01-01");
        rate("INVIGILATOR", "600", "2026-01-06"); // starts the day after the exam: not used for it

        Map<String, Object> pay = pay(past);
        assertThat(pay).containsEntry("periodName", "Jan 2026");
        assertThat(summary(pay)).containsEntry("currency", "INR").containsEntry("total", 1500.00).containsEntry("completed", 3)
                .containsEntry("absent", 0).containsEntry("upcoming", 0).containsEntry("withoutRate", 0);
        assertThat((List<Map<String, Object>>) pay.get("faculty")).hasSize(3).allSatisfy(f -> assertThat(f).containsEntry("amount", 500.00));
        assertThat(((List<Map<String, Object>>) pay.get("departments")).stream().mapToDouble(d -> ((Number) d.get("amount")).doubleValue()).sum())
                .isEqualTo(1500.0);

        // Someone did not turn up: the duty is not paid; marking them present again restores it.
        Map<String, Object> duty = pastDuties().getFirst();
        var absent = attendance(duty, true, duty.get("version"));
        assertThat(absent.getStatusCode()).as(String.valueOf(absent.getBody())).isEqualTo(HttpStatus.OK);
        Map<String, Object> marked = pastDuties().stream().filter(d -> d.get("assignmentId").equals(duty.get("assignmentId"))).findFirst().orElseThrow();
        assertThat(marked).containsEntry("status", "ABSENT");
        assertThat(summary(pay(past))).containsEntry("total", 1000.00).containsEntry("completed", 2).containsEntry("absent", 1);
        List<Object> summaries = field((List<Map<String, Object>>) (List) data().send(GET, pastBase + "/history", college.accessToken(), null,
                List.class).getBody(), "summary");
        assertThat(summaries).anySatisfy(s -> assertThat((String) s).startsWith("Marked Faculty ").contains(" absent "));
        assertThat(attendance(marked, false, marked.get("version")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary(pay(past))).containsEntry("total", 1500.00);

        // Without a rate for the exam date the duties are counted but not priced.
        assertThat(send(DELETE, "/api/v1/remuneration/rates/" + early.get("id") + "?version=" + early.get("version"), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(summary(pay(past))).containsEntry("total", 0.00).containsEntry("withoutRate", 3);

        // A future exam is not paid yet.
        generate();
        publishCurrentDraft();
        assertThat(summary(pay(period))).containsEntry("completed", 0).containsEntry("upcoming", 5).containsEntry("total", 0.00);
    }

    @Test
    void ratesAndAttendanceAreChecked() {
        publishPastPeriod();
        Map<String, Object> r = rate("INVIGILATOR", "450.50", "2025-06-01");
        assertThat(r).containsEntry("amount", 450.50).containsEntry("currency", "INR").containsKey("createdByName");
        assertThat(r.get("createdByName")).isNotNull();

        assertThat(send(POST, "/api/v1/remuneration/rates", Map.of("role", "INVIGILATOR", "amount", "400", "effectiveFrom", "2025-06-01"))
                .getBody()).containsEntry("code", "RATE_EXISTS");
        assertThat(send(POST, "/api/v1/remuneration/rates", Map.of("role", "CHIEF", "amount", "-1", "effectiveFrom", "2025-06-01"))
                .getBody()).containsEntry("code", "INVALID_AMOUNT");
        assertThat(send(POST, "/api/v1/remuneration/rates", Map.of("role", "CHIEF", "amount", "10.555", "effectiveFrom", "2025-06-01"))
                .getBody()).containsEntry("code", "INVALID_AMOUNT");
        assertThat(send(POST, "/api/v1/remuneration/rates", Map.of("role", "CHIEF", "amount", "700", "currency", "USD", "effectiveFrom", "2025-06-01"))
                .getBody()).containsEntry("code", "CURRENCY_MISMATCH");
        assertThat(send(DELETE, "/api/v1/remuneration/rates/" + r.get("id") + "?version=9", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List) data().send(GET, "/api/v1/remuneration/rates", college.accessToken(), null, List.class).getBody()).hasSize(1);

        // Attendance only after the session, and only on the published chart.
        generate();
        publishCurrentDraft();
        Map<String, Object> future = duties(chart(), "FN", "H2").getFirst();
        var early = send(PUT, base + "/assignments/" + future.get("assignmentId") + "/attendance",
                Map.of("absent", true, "version", future.get("version")));
        assertThat(early.getBody()).containsEntry("code", "SESSION_NOT_OVER");
        Map<String, Object> duty = pastDuties().getFirst();
        assertThat(attendance(duty, false, duty.get("version")).getBody()).containsEntry("code", "ATTENDANCE_UNCHANGED");
        assertThat(attendance(duty, true, 99).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Faculty and other colleges cannot see or change any of it.
        String faculty = loginAs("F3");
        assertThat(data().send(GET, "/api/v1/remuneration/rates", faculty, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(data().send(GET, "/api/v1/remuneration", faculty, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String outsider = loginAtAnotherCollege(List.of("EXAM_CELL_ADMIN"));
        assertThat((List) data().send(GET, "/api/v1/remuneration/rates", outsider, null, List.class).getBody()).isEmpty();
        assertThat(data().send(GET, "/api/v1/remuneration?periodId=" + past, outsider, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(data().send(DELETE, "/api/v1/remuneration/rates/" + r.get("id") + "?version=0", outsider, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(data().send(PUT, pastBase + "/assignments/" + duty.get("assignmentId") + "/attendance", outsider,
                Map.of("absent", true, "version", duty.get("version")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theRemunerationReportListsPeopleDepartmentsTotalAndRates() {
        publishPastPeriod();
        rate("INVIGILATOR", "500", "2025-01-01");

        var r = data().send(GET, "/api/v1/exam-periods/" + past + "/reports/remuneration?format=csv", college.accessToken(), null, byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getFirst("Content-Disposition")).contains("remuneration-jan-2026.csv");
        List<String> lines = new String(r.getBody(), StandardCharsets.UTF_8).substring(1).lines().toList();
        assertThat(lines).contains("Faculty", "Departments", "Total", "Rates", "Total amount (INR),1500.00", "Invigilator,Wed 1 Jan 2025,500.00");
        assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("Faculty,Code,Department,Completed"));
    }
}
