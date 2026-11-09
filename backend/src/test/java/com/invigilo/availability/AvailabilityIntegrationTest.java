package com.invigilo.availability;

import static com.invigilo.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.policy.PolicySettings;
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
class AvailabilityIntegrationTest extends IntegrationTest {

    record Login(String facultyId, String token) {}

    TestData.Registered college;
    String cse;
    String ece;
    String period;
    String fn;
    String an;
    Login faculty;
    Login hodCse;
    Login hodEce;

    @BeforeEach
    void setUp() {
        college = data().registerCollege();
        cse = id(admin(POST, "/api/v1/departments", Map.of("code", "CSE", "name", "CS", "active", true)));
        ece = id(admin(POST, "/api/v1/departments", Map.of("code", "ECE", "name", "EC", "active", true)));
        period = id(admin(POST, "/api/v1/exam-periods", Map.of("name", "Mar 2027", "startDate", "2027-03-01", "endDate", "2027-03-12")));
        fn = id(admin(POST, "/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2027-03-02", "label", "FN", "startTime", "09:30", "endTime", "12:30")));
        an = id(admin(POST, "/api/v1/exam-periods/" + period + "/sessions", Map.of("date", "2027-03-02", "label", "AN", "startTime", "13:30", "endTime", "16:30")));
        faculty = login("F1", cse, List.of());
        hodCse = login("H1", cse, List.of("HOD"));
        hodEce = login("H2", ece, List.of("HOD"));
    }

    ResponseEntity<Map> admin(org.springframework.http.HttpMethod method, String path, Object body) {
        return data().send(method, path, college.accessToken(), body, Map.class);
    }

    ResponseEntity<Map> as(Login who, String path, Object body) {
        return data().send(POST, path, who.token(), body, Map.class);
    }

    static String id(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        return (String) r.getBody().get("id");
    }

    /** A faculty member with a real login, created through the invitation flow. */
    Login login(String code, String departmentId, List<String> roles) {
        String email = code.toLowerCase() + "." + TestData.unique().toLowerCase() + "@college.example.test";
        Map<String, Object> f = new HashMap<>(Map.of("employeeCode", code, "fullName", "Faculty " + code, "departmentId", departmentId,
                "email", email, "employmentType", "REGULAR", "chiefEligible", false, "dutyWeight", 1, "active", true));
        String facultyId = id(admin(POST, "/api/v1/faculty", f));
        String token = (String) admin(POST, "/api/v1/faculty/" + facultyId + "/access/invitation", Map.of("roles", roles)).getBody().get("token");
        var accepted = http.postForEntity("/api/v1/auth/invitations/accept", json(Map.of("token", token, "password", "faculty-password")), Map.class);
        return new Login(facultyId, (String) accepted.getBody().get("accessToken"));
    }

    void setAvailabilityPolicy(boolean fullDay, boolean session, boolean needsApproval) {
        var current = data().get("/api/v1/policy", college.accessToken(), Map.class).getBody();
        var d = PolicySettings.defaults();
        var settings = new PolicySettings(d.faculty(), d.workload(), d.staffing(),
                new PolicySettings.AvailabilityRules(fullDay, session, needsApproval, true), d.swaps());
        assertThat(admin(PUT, "/api/v1/policy", Map.of("basedOnVersion", current.get("version"), "settings", settings)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    Map<String, Object> wholeDays(String from, String to) {
        return Map.of("fromDate", from, "toDate", to, "type", "LEAVE", "reason", "Family function");
    }

    @Nested
    class SelfService {

        @Test
        void withoutApprovalRequestsTakeEffectImmediately() {
            var r = as(faculty, "/api/v1/me/availability", wholeDays("2027-03-02", "2027-03-03"));

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(r.getBody()).containsEntry("status", "APPROVED").containsEntry("fromDate", "2027-03-02");
            var mine = data().get("/api/v1/me/availability", faculty.token(), List.class).getBody();
            assertThat(mine).hasSize(1);
        }

        @Test
        void sessionRequestTakesItsDateFromTheSession() {
            var r = as(faculty, "/api/v1/me/availability", Map.of("sessionId", an, "type", "UNAVAILABLE"));

            assertThat(r.getBody()).containsEntry("fromDate", "2027-03-02").containsEntry("toDate", "2027-03-02");
            assertThat((Map<String, Object>) r.getBody().get("session")).containsEntry("label", "AN");
        }

        @Test
        void policyLimitsWhatFacultyMayAskFor() {
            setAvailabilityPolicy(false, true, false);
            assertThat(as(faculty, "/api/v1/me/availability", wholeDays("2027-03-02", "2027-03-02")).getBody())
                    .containsEntry("code", "FULL_DAY_NOT_ALLOWED");

            setAvailabilityPolicy(true, false, false);
            assertThat(as(faculty, "/api/v1/me/availability", Map.of("sessionId", fn, "type", "UNAVAILABLE")).getBody())
                    .containsEntry("code", "SESSION_LEVEL_NOT_ALLOWED");
        }

        @Test
        void overlappingAndPastRequestsAreRefused() {
            as(faculty, "/api/v1/me/availability", Map.of("sessionId", fn, "type", "UNAVAILABLE"));

            var sameSession = as(faculty, "/api/v1/me/availability", Map.of("sessionId", fn, "type", "LEAVE"));
            var wholeDayOverSession = as(faculty, "/api/v1/me/availability", wholeDays("2027-03-01", "2027-03-02"));
            var otherSession = as(faculty, "/api/v1/me/availability", Map.of("sessionId", an, "type", "UNAVAILABLE"));
            var past = as(faculty, "/api/v1/me/availability", wholeDays("2020-01-01", "2020-01-02"));

            assertThat(sameSession.getBody()).containsEntry("code", "AVAILABILITY_OVERLAP");
            assertThat(wholeDayOverSession.getBody()).containsEntry("code", "AVAILABILITY_OVERLAP");
            assertThat(otherSession.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(past.getBody()).containsEntry("code", "DATE_IN_PAST");
        }

        @Test
        void facultyCancelOnlyTheirOwnRequests() {
            String id = id(as(faculty, "/api/v1/me/availability", wholeDays("2027-03-04", "2027-03-04")));

            var byOther = as(hodEce, "/api/v1/availability/" + id + "/cancel", null);
            var byOwner = as(faculty, "/api/v1/availability/" + id + "/cancel", null);
            var again = as(faculty, "/api/v1/availability/" + id + "/cancel", null);

            assertThat(byOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(byOwner.getBody()).containsEntry("status", "CANCELLED");
            assertThat(again.getBody()).containsEntry("code", "ALREADY_DECIDED");
            // A cancelled request no longer blocks a new one for the same day.
            assertThat(as(faculty, "/api/v1/me/availability", wholeDays("2027-03-04", "2027-03-04")).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        @Test
        void loginsWithoutAFacultyRecordCannotRequest() {
            var r = data().send(POST, "/api/v1/me/availability", college.accessToken(), wholeDays("2027-03-02", "2027-03-02"), Map.class);
            assertThat(r.getBody()).containsEntry("code", "NOT_A_FACULTY_MEMBER");
        }
    }

    @Nested
    class Approval {

        @BeforeEach
        void requireApproval() {
            setAvailabilityPolicy(true, true, true);
        }

        @Test
        void hodOfTheDepartmentApproves() {
            String id = id(as(faculty, "/api/v1/me/availability", wholeDays("2027-03-05", "2027-03-05")));
            assertThat(data().get("/api/v1/me/availability", faculty.token(), List.class).getBody())
                    .extracting(r -> ((Map<String, Object>) r).get("status")).containsExactly("PENDING");

            var hodList = data().get("/api/v1/availability?status=PENDING", hodCse.token(), List.class).getBody();
            var otherHodList = data().get("/api/v1/availability?status=PENDING", hodEce.token(), List.class).getBody();
            assertThat(hodList).hasSize(1);
            assertThat(otherHodList).isEmpty();

            var wrongHod = as(hodEce, "/api/v1/availability/" + id + "/approve", Map.of("note", "ok"));
            var approved = as(hodCse, "/api/v1/availability/" + id + "/approve", Map.of("note", "Approved"));
            var twice = as(hodCse, "/api/v1/availability/" + id + "/reject", Map.of());

            assertThat(wrongHod.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(approved.getBody()).containsEntry("status", "APPROVED").containsEntry("reviewNote", "Approved")
                    .containsEntry("reviewedByName", "Faculty H1");
            assertThat(twice.getBody()).containsEntry("code", "ALREADY_DECIDED");
        }

        @Test
        void nobodyReviewsTheirOwnRequestButTheExamCellCan() {
            String own = id(as(hodCse, "/api/v1/me/availability", wholeDays("2027-03-06", "2027-03-06")));

            assertThat(as(hodCse, "/api/v1/availability/" + own + "/approve", null).getBody()).containsEntry("code", "OWN_REQUEST");
            assertThat(admin(POST, "/api/v1/availability/" + own + "/reject", Map.of("note", "Exams that day")).getBody())
                    .containsEntry("status", "REJECTED");
        }

        @Test
        void plainFacultyCannotReview() {
            String id = id(as(hodCse, "/api/v1/me/availability", wholeDays("2027-03-07", "2027-03-07")));
            assertThat(as(faculty, "/api/v1/availability/" + id + "/approve", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(data().get("/api/v1/availability", faculty.token(), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class ExamCell {

        @Test
        void recordedLeaveIsApprovedAndIgnoresSelfServiceLimits() {
            setAvailabilityPolicy(false, false, true);

            var r = admin(POST, "/api/v1/availability", Map.of("facultyId", faculty.facultyId(), "sessionId", fn, "type", "LEAVE",
                    "reason", "Medical"));

            assertThat(r.getBody()).containsEntry("status", "APPROVED").containsEntry("requestedByName", "Admin " + college.collegeCode().substring(1));
        }

        @Test
        void periodOverviewShowsWhoIsUnavailablePerSession() {
            setAvailabilityPolicy(true, true, true);
            // Whole-day leave (approved by the exam cell) covers both sessions of 2 March.
            admin(POST, "/api/v1/availability", Map.of("facultyId", faculty.facultyId(), "fromDate", "2027-03-02",
                    "toDate", "2027-03-02", "type", "LEAVE"));
            // A pending request for the AN session only.
            as(hodEce, "/api/v1/me/availability", Map.of("sessionId", an, "type", "UNAVAILABLE"));

            var overview = (List<Map<String, Object>>) (List) data().get("/api/v1/exam-periods/" + period + "/availability",
                    college.accessToken(), List.class).getBody();

            var fnRow = overview.stream().filter(o -> "FN".equals(((Map) o.get("session")).get("label"))).findFirst().orElseThrow();
            var anRow = overview.stream().filter(o -> "AN".equals(((Map) o.get("session")).get("label"))).findFirst().orElseThrow();
            assertThat((List<Map<String, Object>>) fnRow.get("unavailable")).extracting(u -> ((Map) u.get("faculty")).get("code"))
                    .containsExactly("F1");
            assertThat((List<?>) fnRow.get("pending")).isEmpty();
            assertThat((List<Map<String, Object>>) anRow.get("pending")).extracting(p -> p.get("code")).containsExactly("H2");
        }

        @Test
        void anotherCollegeCannotSeeOrDecideRequests() {
            setAvailabilityPolicy(true, true, true);
            String id = id(as(faculty, "/api/v1/me/availability", wholeDays("2027-03-08", "2027-03-08")));
            var other = data().registerCollege();

            assertThat(data().send(POST, "/api/v1/availability/" + id + "/approve", other.accessToken(), null, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(data().get("/api/v1/availability", other.accessToken(), List.class).getBody()).isEmpty();
            assertThat(data().send(POST, "/api/v1/availability", other.accessToken(),
                    Map.of("facultyId", faculty.facultyId(), "fromDate", "2027-03-09", "toDate", "2027-03-09", "type", "LEAVE"), Map.class)
                    .getBody()).containsEntry("code", "FACULTY_NOT_FOUND");
        }
    }
}
