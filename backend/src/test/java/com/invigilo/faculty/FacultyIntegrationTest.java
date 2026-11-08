package com.invigilo.faculty;

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
class FacultyIntegrationTest extends IntegrationTest {

    TestData.Registered a;
    String deptId;
    String subj1;
    String subj2;
    String hallId;

    @BeforeEach
    void setUp() {
        a = data().registerCollege();
        deptId = (String) post("/api/v1/departments", Map.of("code", "CSE", "name", "Computer Science", "active", true)).getBody().get("id");
        subj1 = (String) post("/api/v1/subjects", Map.of("code", "CS301", "name", "Algorithms", "departmentId", deptId, "active", true)).getBody().get("id");
        subj2 = (String) post("/api/v1/subjects", Map.of("code", "CS302", "name", "Compilers", "departmentId", deptId, "active", true)).getBody().get("id");
        hallId = (String) post("/api/v1/halls", Map.of("code", "H1", "name", "Hall 1", "capacity", 60, "minInvigilators", 1,
                "lab", false, "active", true)).getBody().get("id");
    }

    ResponseEntity<Map> post(String path, Object body) {
        return data().send(POST, path, a.accessToken(), body, Map.class);
    }

    Map<String, Object> faculty(String code, Object... subjectIds) {
        Map<String, Object> f = new HashMap<>();
        f.put("employeeCode", code);
        f.put("fullName", "Faculty " + code);
        f.put("departmentId", deptId);
        f.put("email", code.toLowerCase() + "@college.example.test");
        f.put("designation", "Assistant Professor");
        f.put("employmentType", "REGULAR");
        f.put("joinedOn", "2015-06-01");
        f.put("chiefEligible", true);
        f.put("dutyWeight", 1.0);
        f.put("active", true);
        f.put("subjectIds", List.of(subjectIds));
        return f;
    }

    Map<String, Object> create(String code, Object... subjectIds) {
        var r = post("/api/v1/faculty", faculty(code, subjectIds));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @Nested
    class Records {

        @Test
        void createWithSubjectsThenChangeThem() {
            var created = create("F100", subj1);
            assertThat(created).containsEntry("employeeCode", "F100").containsEntry("email", "f100@college.example.test");
            assertThat((List<Map<String, Object>>) created.get("subjects")).extracting(s -> s.get("code")).containsExactly("CS301");

            var body = faculty("F100", subj2, subj1);
            body.put("version", created.get("version"));
            var updated = data().send(PUT, "/api/v1/faculty/" + created.get("id"), a.accessToken(), body, Map.class);
            assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<Map<String, Object>>) updated.getBody().get("subjects")).extracting(s -> s.get("code"))
                    .containsExactly("CS301", "CS302");

            body = faculty("F100");
            body.put("version", updated.getBody().get("version"));
            var cleared = data().send(PUT, "/api/v1/faculty/" + created.get("id"), a.accessToken(), body, Map.class);
            assertThat((List<?>) cleared.getBody().get("subjects")).isEmpty();
        }

        @Test
        void searchFindsByNameCodeOrEmailAndFiltersByType() {
            create("F200");
            var visiting = faculty("V300");
            visiting.put("fullName", "Visiting Ravi");
            visiting.put("employmentType", "VISITING");
            post("/api/v1/faculty", visiting);

            var byName = data().get("/api/v1/faculty?q=ravi", a.accessToken(), Map.class).getBody();
            var byType = data().get("/api/v1/faculty?employmentType=VISITING", a.accessToken(), Map.class).getBody();
            var all = data().get("/api/v1/faculty", a.accessToken(), Map.class).getBody();

            assertThat(byName).containsEntry("total", 1);
            assertThat(byType).containsEntry("total", 1);
            assertThat(all).containsEntry("total", 2);
        }

        @Test
        void employeeCodeAndEmailAreUnique() {
            create("F300");
            var dupCode = faculty("f300");
            dupCode.put("email", "other@college.example.test");
            var dupEmail = faculty("F301");
            dupEmail.put("email", "F300@College.Example.Test");

            assertThat(post("/api/v1/faculty", dupCode).getBody()).containsEntry("code", "FACULTY_CODE_TAKEN");
            assertThat(post("/api/v1/faculty", dupEmail).getBody()).containsEntry("code", "FACULTY_EMAIL_TAKEN");
        }

        @Test
        void validatesWeightAndDates() {
            var bad = faculty("F400");
            bad.put("dutyWeight", 3.5);
            bad.put("joinedOn", "2999-01-01");
            bad.put("employmentType", null);

            var r = post("/api/v1/faculty", bad);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((Map<String, String>) r.getBody().get("fieldErrors")).containsOnlyKeys("dutyWeight", "joinedOn", "employmentType");
        }

        @Test
        void onlyExamCellCanSeeFaculty() {
            String hod = signInAsNewMember(a.collegeId(), Role.HOD, Role.FACULTY);
            assertThat(data().get("/api/v1/faculty", hod, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void referencedRecordsCannotBeDeleted() {
            var f = create("F500", subj1);

            var deptDelete = data().send(DELETE, "/api/v1/departments/" + deptId, a.accessToken(), null, Map.class);
            var subjectDelete = data().send(DELETE, "/api/v1/subjects/" + subj1, a.accessToken(), null, Map.class);
            var unusedSubjectDelete = data().send(DELETE, "/api/v1/subjects/" + subj2, a.accessToken(), null, Map.class);
            var facultyDelete = data().send(DELETE, "/api/v1/faculty/" + f.get("id"), a.accessToken(), null, Map.class);

            assertThat(deptDelete.getBody()).containsEntry("code", "DEPARTMENT_IN_USE");
            assertThat((String) deptDelete.getBody().get("detail")).contains("faculty member(s)");
            assertThat(subjectDelete.getBody()).containsEntry("code", "SUBJECT_IN_USE");
            assertThat(unusedSubjectDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(facultyDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // no login yet
        }
    }

    @Nested
    class Restrictions {

        @Test
        void addListAndRemove() {
            var f = create("F600");
            String base = "/api/v1/faculty/" + f.get("id") + "/restrictions";

            var hall = post(base, Map.of("type", "EXCLUDE_HALL", "hallId", hallId, "reason", "Mobility"));
            var max = post(base, Map.of("type", "MAX_DUTIES_PER_DAY", "maxValue", 1));
            var notChief = post(base, Map.of("type", "NOT_CHIEF"));
            assertThat(hall.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(max.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(notChief.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            var detail = data().get("/api/v1/faculty/" + f.get("id"), a.accessToken(), Map.class).getBody();
            assertThat((List<Map<String, Object>>) detail.get("restrictions")).extracting(r -> r.get("type"))
                    .containsExactly("EXCLUDE_HALL", "MAX_DUTIES_PER_DAY", "NOT_CHIEF");

            var hallDelete = data().send(DELETE, "/api/v1/halls/" + hallId, a.accessToken(), null, Map.class);
            assertThat(hallDelete.getBody()).containsEntry("code", "HALL_IN_USE");

            var removed = data().send(DELETE, base + "/" + hall.getBody().get("id"), a.accessToken(), null, Map.class);
            assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        void rejectsMalformedAndDuplicateRestrictions() {
            var f = create("F700");
            String base = "/api/v1/faculty/" + f.get("id") + "/restrictions";

            var missingHall = post(base, Map.of("type", "EXCLUDE_HALL"));
            var extraValue = post(base, Map.of("type", "NOT_CHIEF", "maxValue", 2));
            post(base, Map.of("type", "MAX_DUTIES_PER_PERIOD", "maxValue", 4));
            var duplicate = post(base, Map.of("type", "MAX_DUTIES_PER_PERIOD", "maxValue", 6));

            assertThat(missingHall.getBody()).containsEntry("code", "INVALID_RESTRICTION");
            assertThat(extraValue.getBody()).containsEntry("code", "INVALID_RESTRICTION");
            assertThat(duplicate.getBody()).containsEntry("code", "RESTRICTION_EXISTS");
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void anotherCollegeCannotSeeOrUseThisCollegesFaculty() {
            var f = create("F800");
            var b = data().registerCollege();
            String bDept = (String) data().send(POST, "/api/v1/departments", b.accessToken(),
                    Map.of("code", "CSE", "name", "CS", "active", true), Map.class).getBody().get("id");

            assertThat(data().get("/api/v1/faculty/" + f.get("id"), b.accessToken(), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(data().send(POST, "/api/v1/faculty/" + f.get("id") + "/restrictions", b.accessToken(),
                    Map.of("type", "NOT_CHIEF"), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            // B's faculty cannot teach A's subject or be restricted from A's hall.
            var stolenSubject = faculty("B100", subj1);
            stolenSubject.put("departmentId", bDept);
            assertThat(data().send(POST, "/api/v1/faculty", b.accessToken(), stolenSubject, Map.class).getBody())
                    .containsEntry("code", "SUBJECT_NOT_FOUND");
            var own = faculty("B101");
            own.put("departmentId", bDept);
            String bFaculty = (String) data().send(POST, "/api/v1/faculty", b.accessToken(), own, Map.class).getBody().get("id");
            assertThat(data().send(POST, "/api/v1/faculty/" + bFaculty + "/restrictions", b.accessToken(),
                    Map.of("type", "EXCLUDE_HALL", "hallId", hallId), Map.class).getBody())
                    .containsEntry("code", "HALL_NOT_FOUND");
        }
    }
}
