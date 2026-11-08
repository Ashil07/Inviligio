package com.invigilo.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.identity.Role;
import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"unchecked", "rawtypes"})
class ScheduleIntegrationTest extends IntegrationTest {

    TestData.Registered a;
    String cs301;
    String cs391;
    String h1;
    String lab;
    String period;

    @BeforeEach
    void setUp() {
        a = data().registerCollege();
        String dept = (String) post("/api/v1/departments", Map.of("code", "CSE", "name", "CS", "active", true)).getBody().get("id");
        cs301 = (String) post("/api/v1/subjects", Map.of("code", "CS301", "name", "Algorithms", "departmentId", dept, "active", true)).getBody().get("id");
        cs391 = (String) post("/api/v1/subjects", Map.of("code", "CS391", "name", "Lab", "departmentId", dept, "active", true)).getBody().get("id");
        h1 = (String) post("/api/v1/halls", Map.of("code", "H1", "name", "Hall 1", "capacity", 60, "minInvigilators", 1, "lab", false, "active", true)).getBody().get("id");
        lab = (String) post("/api/v1/halls", Map.of("code", "LAB1", "name", "Lab 1", "capacity", 30, "minInvigilators", 1, "lab", true, "active", true)).getBody().get("id");
        period = (String) post("/api/v1/exam-periods", Map.of("name", "Nov 2026", "startDate", "2026-11-16", "endDate", "2026-11-27")).getBody().get("id");
    }

    ResponseEntity<Map> post(String path, Object body) {
        return data().send(POST, path, a.accessToken(), body, Map.class);
    }

    ResponseEntity<Map> put(String path, Object body) {
        return data().send(PUT, path, a.accessToken(), body, Map.class);
    }

    Map<String, Object> session(String date, String label, String start, String end) {
        var r = post("/api/v1/exam-periods/" + period + "/sessions", Map.of("date", date, "label", label, "startTime", start, "endTime", end));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static String examId(Map<String, Object> detail, String subjectCode) {
        return ((List<Map<String, Object>>) detail.get("exams")).stream()
                .filter(e -> subjectCode.equals(((Map<String, Object>) e.get("subject")).get("code")))
                .map(e -> (String) e.get("id")).findFirst().orElseThrow();
    }

    static Map<String, Object> hall(String hallId, Object... seats) {
        Map<String, Object> body = new HashMap<>();
        body.put("hallId", hallId);
        List<Map<String, Object>> seating = new java.util.ArrayList<>();
        for (int i = 0; i < seats.length; i += 2) seating.add(Map.of("examId", seats[i], "studentCount", seats[i + 1]));
        body.put("seating", seating);
        return body;
    }

    @Nested
    class Periods {

        @Test
        void sessionsMustFallInsideThePeriodAndBeUnique() {
            session("2026-11-16", "fn", "09:30", "12:30");

            var outside = post("/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2026-12-01", "label", "FN", "startTime", "09:30", "endTime", "12:30"));
            var dup = post("/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2026-11-16", "label", "FN", "startTime", "10:00", "endTime", "13:00"));
            var backwards = post("/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2026-11-17", "label", "AN", "startTime", "16:00", "endTime", "13:00"));

            assertThat(outside.getBody()).containsEntry("code", "SESSION_OUTSIDE_PERIOD");
            assertThat(dup.getBody()).containsEntry("code", "SESSION_EXISTS");
            assertThat(backwards.getBody()).containsEntry("code", "INVALID_TIMES");
        }

        @Test
        void bulkGenerationSkipsSundaysAndExistingSessions() {
            session("2026-11-16", "FN", "09:30", "12:30");

            var r = post("/api/v1/exam-periods/" + period + "/sessions/bulk", Map.of(
                    "fromDate", "2026-11-16", "toDate", "2026-11-22", "skipDays", List.of("SUNDAY"),
                    "slots", List.of(Map.of("label", "FN", "startTime", "09:30", "endTime", "12:30"),
                            Map.of("label", "AN", "startTime", "13:30", "endTime", "16:30"))));

            // Mon 16 .. Sat 21 = 6 days x 2 slots = 12, one already existed.
            assertThat(r.getBody()).containsEntry("created", 11).containsEntry("skipped", 1);
            var list = data().get("/api/v1/exam-periods/" + period + "/sessions", a.accessToken(), List.class).getBody();
            assertThat(list).hasSize(12);
            assertThat(data().get("/api/v1/exam-periods/" + period, a.accessToken(), Map.class).getBody()).containsEntry("sessionCount", 12);
        }

        @Test
        void periodDatesCannotExcludeExistingSessions() {
            session("2026-11-27", "FN", "09:30", "12:30");

            var r = put("/api/v1/exam-periods/" + period, Map.of("name", "Nov 2026", "startDate", "2026-11-16", "endDate", "2026-11-20", "version", 0));

            assertThat(r.getBody()).containsEntry("code", "PERIOD_EXCLUDES_SESSIONS");
        }

        @Test
        void deletingAPeriodRemovesItsSchedule() {
            var s = session("2026-11-16", "FN", "09:30", "12:30");
            post("/api/v1/exam-sessions/" + s.get("id") + "/exams", Map.of("subjectId", cs301, "type", "THEORY"));

            assertThat(data().send(DELETE, "/api/v1/exam-periods/" + period, a.accessToken(), null, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(data().get("/api/v1/exam-sessions/" + s.get("id"), a.accessToken(), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            // The subject is free again.
            assertThat(data().send(DELETE, "/api/v1/subjects/" + cs301, a.accessToken(), null, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    @Nested
    class Seating {

        @Test
        void mixedSeatingComputesStaffingAndWarnings() {
            var s = session("2026-11-16", "FN", "09:30", "12:30");
            String sid = (String) s.get("id");
            var withExams = post("/api/v1/exam-sessions/" + sid + "/exams", Map.of("subjectId", cs301, "type", "THEORY", "registeredStudents", 70)).getBody();
            withExams = post("/api/v1/exam-sessions/" + sid + "/exams", Map.of("subjectId", cs391, "type", "PRACTICAL")).getBody();
            String e301 = examId(withExams, "CS301");
            String e391 = examId(withExams, "CS391");

            var detail = post("/api/v1/exam-sessions/" + sid + "/halls", hall(h1, e301, 45, e391, 10)).getBody();

            var hallView = ((List<Map<String, Object>>) detail.get("halls")).getFirst();
            assertThat(hallView).containsEntry("totalStudents", 55);
            // Default policy: 30 per invigilator, no practical ratio -> ceil(55/30) = 2.
            assertThat((Map<String, Object>) hallView.get("staffing")).containsEntry("total", 2);
            assertThat(detail).containsEntry("totalPositions", 2);
            assertThat((List<String>) detail.get("warnings")).contains(
                    "CS301: 25 of 70 registered students are not seated",
                    "A practical exam is seated in hall H1, which is not a lab");
        }

        @Test
        void capacityRegistrationAndSessionMembershipAreEnforced() {
            String sid = (String) session("2026-11-16", "FN", "09:30", "12:30").get("id");
            var d = post("/api/v1/exam-sessions/" + sid + "/exams", Map.of("subjectId", cs301, "type", "THEORY", "registeredStudents", 40)).getBody();
            String e301 = examId(d, "CS301");
            String other = (String) session("2026-11-17", "FN", "09:30", "12:30").get("id");
            var otherExam = examId(post("/api/v1/exam-sessions/" + other + "/exams", Map.of("subjectId", cs391, "type", "THEORY")).getBody(), "CS391");

            assertThat(post("/api/v1/exam-sessions/" + sid + "/halls", hall(lab, e301, 35)).getBody()).containsEntry("code", "HALL_OVER_CAPACITY");
            assertThat(post("/api/v1/exam-sessions/" + sid + "/halls", hall(h1, e301, 41)).getBody()).containsEntry("code", "EXAM_OVER_SEATED");
            assertThat(post("/api/v1/exam-sessions/" + sid + "/halls", hall(h1, otherExam, 10)).getBody()).containsEntry("code", "EXAM_NOT_IN_SESSION");

            post("/api/v1/exam-sessions/" + sid + "/halls", hall(h1, e301, 30));
            // 30 in H1 + 15 in LAB1 = 45 > 40 registered.
            assertThat(post("/api/v1/exam-sessions/" + sid + "/halls", hall(lab, e301, 15)).getBody()).containsEntry("code", "EXAM_OVER_SEATED");
            assertThat(post("/api/v1/exam-sessions/" + sid + "/halls", hall(h1, e301, 5)).getBody()).containsEntry("code", "HALL_ALREADY_IN_SESSION");
        }

        @Test
        void aHallCannotHostOverlappingSessions() {
            String fn = (String) session("2026-11-16", "FN", "09:30", "12:30").get("id");
            String practical = (String) session("2026-11-16", "PR", "11:00", "14:00").get("id");
            String an = (String) session("2026-11-16", "AN", "13:30", "16:30").get("id");
            post("/api/v1/exam-sessions/" + fn + "/halls", hall(h1));

            var clash = post("/api/v1/exam-sessions/" + practical + "/halls", hall(h1));
            var fine = post("/api/v1/exam-sessions/" + an + "/halls", hall(h1));

            assertThat(clash.getBody()).containsEntry("code", "HALL_BUSY");
            assertThat((String) clash.getBody().get("detail")).contains("FN session");
            assertThat(fine.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // Moving the AN session earlier would now clash in H1.
            var anDetail = data().get("/api/v1/exam-sessions/" + an, a.accessToken(), Map.class).getBody();
            var move = put("/api/v1/exam-sessions/" + an, Map.of("date", "2026-11-16", "label", "AN", "startTime", "12:00",
                    "endTime", "15:00", "version", anDetail.get("version")));
            assertThat(move.getBody()).containsEntry("code", "HALL_BUSY");
        }

        @Test
        void overrideNeedsAReasonAndReplacesTheCount() {
            String sid = (String) session("2026-11-16", "FN", "09:30", "12:30").get("id");
            String e301 = examId(post("/api/v1/exam-sessions/" + sid + "/exams", Map.of("subjectId", cs301, "type", "THEORY")).getBody(), "CS301");
            var d = post("/api/v1/exam-sessions/" + sid + "/halls", hall(h1, e301, 20)).getBody();
            var sh = ((List<Map<String, Object>>) d.get("halls")).getFirst();

            var noReason = hall(h1, e301, 20);
            noReason.put("requiredOverride", 3);
            noReason.put("version", sh.get("version"));
            assertThat(put("/api/v1/session-halls/" + sh.get("id"), noReason).getBody()).containsEntry("code", "OVERRIDE_REASON_REQUIRED");

            noReason.put("overrideReason", "Students with scribes");
            var updated = put("/api/v1/session-halls/" + sh.get("id"), noReason).getBody();
            var hallView = ((List<Map<String, Object>>) updated.get("halls")).getFirst();
            assertThat((Map<String, Object>) hallView.get("staffing")).containsEntry("total", 3);
        }

        @Test
        void usedSubjectsAndHallsCannotBeDeleted() {
            String sid = (String) session("2026-11-16", "FN", "09:30", "12:30").get("id");
            post("/api/v1/exam-sessions/" + sid + "/exams", Map.of("subjectId", cs301, "type", "THEORY"));
            post("/api/v1/exam-sessions/" + sid + "/halls", hall(h1));

            assertThat(data().send(DELETE, "/api/v1/subjects/" + cs301, a.accessToken(), null, Map.class).getBody())
                    .containsEntry("code", "SUBJECT_IN_USE");
            assertThat(data().send(DELETE, "/api/v1/halls/" + h1, a.accessToken(), null, Map.class).getBody())
                    .containsEntry("code", "HALL_IN_USE");
        }

        @Test
        void sameSubjectOnlyOncePerSession() {
            String sid = (String) session("2026-11-16", "FN", "09:30", "12:30").get("id");
            post("/api/v1/exam-sessions/" + sid + "/exams", Map.of("subjectId", cs301, "type", "THEORY"));
            assertThat(post("/api/v1/exam-sessions/" + sid + "/exams", Map.of("subjectId", cs301, "type", "THEORY")).getBody())
                    .containsEntry("code", "EXAM_EXISTS");
        }
    }

    @Nested
    class AccessAndIsolation {

        @Test
        void membersReadTheTimetableButOnlyTheExamCellChangesIt() {
            String sid = (String) session("2026-11-16", "FN", "09:30", "12:30").get("id");
            String faculty = signInAsNewMember(a.collegeId(), Role.FACULTY);

            assertThat(data().get("/api/v1/exam-periods", faculty, List.class).getBody()).hasSize(1);
            assertThat(data().get("/api/v1/exam-sessions/" + sid, faculty, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(data().send(POST, "/api/v1/exam-sessions/" + sid + "/exams", faculty,
                    Map.of("subjectId", cs301, "type", "THEORY"), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void anotherCollegeSeesNothingAndCannotUseThisCollegesRecords() {
            String sid = (String) session("2026-11-16", "FN", "09:30", "12:30").get("id");
            var b = data().registerCollege();
            String bPeriod = (String) data().send(POST, "/api/v1/exam-periods", b.accessToken(),
                    Map.of("name", "B", "startDate", "2026-11-16", "endDate", "2026-11-20"), Map.class).getBody().get("id");
            String bSession = (String) data().send(POST, "/api/v1/exam-periods/" + bPeriod + "/sessions", b.accessToken(),
                    Map.of("date", "2026-11-16", "label", "FN", "startTime", "09:30", "endTime", "12:30"), Map.class).getBody().get("id");

            assertThat(data().get("/api/v1/exam-periods", b.accessToken(), List.class).getBody()).hasSize(1);
            assertThat(data().get("/api/v1/exam-sessions/" + sid, b.accessToken(), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(data().send(POST, "/api/v1/exam-sessions/" + bSession + "/exams", b.accessToken(),
                    Map.of("subjectId", cs301, "type", "THEORY"), Map.class).getBody()).containsEntry("code", "SUBJECT_NOT_FOUND");
            assertThat(data().send(POST, "/api/v1/exam-sessions/" + bSession + "/halls", b.accessToken(), hall(h1), Map.class).getBody())
                    .containsEntry("code", "HALL_NOT_FOUND");
            // College B's use of its own hall at the same time is unaffected by college A.
        }
    }
}
