package com.invigilo.department;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.audit.AuditRepository;
import com.invigilo.identity.Role;
import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Departments, subjects and halls: CRUD, validation, roles, optimistic locking, tenant isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AcademicStructureIntegrationTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditRepository auditLog;

    TestData.Registered a;
    TestData.Registered b;

    @BeforeEach
    void colleges() {
        a = data().registerCollege();
        b = data().registerCollege();
    }

    ResponseEntity<Map> post(TestData.Registered c, String path, Object body) {
        return data().send(POST, path, c.accessToken(), body, Map.class);
    }

    Map<String, Object> createDepartment(TestData.Registered c, String code) {
        var r = post(c, "/api/v1/departments", Map.of("code", code, "name", "Dept " + code, "active", true));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    Map<String, Object> createSubject(TestData.Registered c, String code, Object departmentId) {
        var body = new HashMap<String, Object>(Map.of("code", code, "name", "Subject " + code,
                "departmentId", departmentId, "active", true));
        body.put("semester", 5);
        body.put("programme", "B.Tech");
        var r = post(c, "/api/v1/subjects", body);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static Map<String, Object> hall(String code, int capacity) {
        return new HashMap<>(Map.of("code", code, "name", "Hall " + code, "capacity", capacity,
                "minInvigilators", 1, "lab", false, "active", true));
    }

    @Nested
    class Departments {

        @Test
        void createListUpdateAndAudit() {
            var created = createDepartment(a, "cse");
            assertThat(created).containsEntry("code", "CSE").containsEntry("active", true).containsEntry("version", 0);

            var update = data().send(PUT, "/api/v1/departments/" + created.get("id"), a.accessToken(),
                    Map.of("code", "CSE", "name", "Computer Science", "active", false, "version", 0), Map.class);
            assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(update.getBody()).containsEntry("name", "Computer Science").containsEntry("version", 1);

            var activeOnly = data().get("/api/v1/departments", a.accessToken(), List.class).getBody();
            var all = data().get("/api/v1/departments?includeInactive=true", a.accessToken(), List.class).getBody();
            assertThat(activeOnly).isEmpty();
            assertThat(all).hasSize(1);

            var trail = tenants.asTenant(a.collegeId(), () -> auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                    "department", UUID.fromString((String) created.get("id"))));
            // Both entries can share a timestamp (coarse clocks), so order is not asserted.
            assertThat(trail).extracting(e -> e.getAction()).containsExactlyInAnyOrder("DEPARTMENT_UPDATED", "DEPARTMENT_CREATED");
        }

        @Test
        void codesAreUniquePerCollegeCaseInsensitively() {
            createDepartment(a, "ECE");

            var duplicate = post(a, "/api/v1/departments", Map.of("code", "ece", "name", "Again", "active", true));
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicate.getBody()).containsEntry("code", "DEPARTMENT_CODE_TAKEN");
            // Another college may use the same code.
            createDepartment(b, "ECE");
        }

        @Test
        void validatesInput() {
            var r = post(a, "/api/v1/departments", Map.of("code", "bad code", "name", "", "active", true));

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((Map<String, String>) r.getBody().get("fieldErrors")).containsOnlyKeys("code", "name");
        }

        @Test
        void staleEditIsRefused() {
            var d = createDepartment(a, "ME");
            String path = "/api/v1/departments/" + d.get("id");
            data().send(PUT, path, a.accessToken(), Map.of("code", "ME", "name", "First", "active", true, "version", 0), Map.class);

            var stale = data().send(PUT, path, a.accessToken(),
                    Map.of("code", "ME", "name", "Second", "active", true, "version", 0), Map.class);
            var missing = data().send(PUT, path, a.accessToken(), Map.of("code", "ME", "name", "Third", "active", true), Map.class);

            assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(stale.getBody()).containsEntry("code", "STALE_UPDATE");
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void departmentWithSubjectsCannotBeDeletedButAnEmptyOneCan() {
            var used = createDepartment(a, "CIV");
            var unused = createDepartment(a, "EEE");
            createSubject(a, "CE201", used.get("id"));

            var refused = data().send(DELETE, "/api/v1/departments/" + used.get("id"), a.accessToken(), null, Map.class);
            var deleted = data().send(DELETE, "/api/v1/departments/" + unused.get("id"), a.accessToken(), null, Map.class);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody()).containsEntry("code", "DEPARTMENT_IN_USE");
            assertThat((String) refused.getBody().get("detail")).contains("1 subject(s)");
            assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        void facultyCanReadButNotWrite() {
            createDepartment(a, "MATH");
            String faculty = signInAsNewMember(a.collegeId(), Role.FACULTY);

            assertThat(data().get("/api/v1/departments", faculty, List.class).getBody()).hasSize(1);
            assertThat(data().send(POST, "/api/v1/departments", faculty,
                    Map.of("code", "X", "name", "X", "active", true), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void malformedIdIsABadRequest() {
            assertThat(data().get("/api/v1/departments/not-a-uuid", a.accessToken(), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class Subjects {

        @Test
        void searchFiltersAndPages() {
            var cse = createDepartment(a, "CS");
            var ece = createDepartment(a, "EC");
            createSubject(a, "CS301", cse.get("id"));
            createSubject(a, "CS302", cse.get("id"));
            createSubject(a, "EC301", ece.get("id"));

            var byDept = data().get("/api/v1/subjects?departmentId=" + cse.get("id"), a.accessToken(), Map.class).getBody();
            var byText = data().get("/api/v1/subjects?q=ec3", a.accessToken(), Map.class).getBody();
            var paged = data().get("/api/v1/subjects?size=2&page=1", a.accessToken(), Map.class).getBody();

            assertThat(byDept).containsEntry("total", 2);
            assertThat((List<Map<String, Object>>) byText.get("items")).extracting(s -> s.get("code")).containsExactly("EC301");
            assertThat(paged).containsEntry("total", 3).containsEntry("page", 1);
            assertThat((List<?>) paged.get("items")).hasSize(1);
            assertThat((Map<String, Object>) ((List<Map<String, Object>>) byDept.get("items")).getFirst().get("department"))
                    .containsEntry("code", "CS");
        }

        @Test
        void wildcardsInSearchAreLiteral() {
            var d = createDepartment(a, "PH");
            createSubject(a, "PH101", d.get("id"));

            var r = data().get("/api/v1/subjects?q=%25", a.accessToken(), Map.class).getBody();

            assertThat(r).containsEntry("total", 0);
        }

        @Test
        void rejectsInactiveDepartmentAndBadSemester() {
            var d = createDepartment(a, "CH");
            data().send(PUT, "/api/v1/departments/" + d.get("id"), a.accessToken(),
                    Map.of("code", "CH", "name", "Chem", "active", false, "version", 0), Map.class);

            var inactive = post(a, "/api/v1/subjects", Map.of("code", "CH101", "name", "Chem", "departmentId", d.get("id"), "active", true));
            var badSemester = post(a, "/api/v1/subjects", Map.of("code", "CH102", "name", "Chem", "departmentId", d.get("id"),
                    "active", true, "semester", 13));

            assertThat(inactive.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(inactive.getBody()).containsEntry("code", "DEPARTMENT_INACTIVE");
            assertThat(badSemester.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void invalidPageSizeIsABadRequest() {
            assertThat(data().get("/api/v1/subjects?size=1000", a.accessToken(), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class Halls {

        @Test
        void crudWithCapacityRules() {
            var created = post(a, "/api/v1/halls", hall("h101", 60));
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getBody()).containsEntry("code", "H101").containsEntry("capacity", 60);

            var tooBig = post(a, "/api/v1/halls", hall("H102", 5000));
            assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            var lab = hall("LAB1", 30);
            lab.put("lab", true);
            lab.put("minInvigilators", 2);
            lab.put("version", 0);
            var updated = data().send(PUT, "/api/v1/halls/" + created.getBody().get("id"), a.accessToken(), lab, Map.class);
            assertThat(updated.getBody()).containsEntry("code", "LAB1").containsEntry("lab", true).containsEntry("minInvigilators", 2);

            var deleted = data().send(DELETE, "/api/v1/halls/" + created.getBody().get("id"), a.accessToken(), null, Map.class);
            assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(data().get("/api/v1/halls?includeInactive=true", a.accessToken(), List.class).getBody()).isEmpty();
        }

        @Test
        void duplicateCodeIsRefused() {
            post(a, "/api/v1/halls", hall("MAIN", 100));
            var dup = post(a, "/api/v1/halls", hall("main", 100));
            assertThat(dup.getBody()).containsEntry("code", "HALL_CODE_TAKEN");
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void otherCollegesRecordsAreInvisibleAndUntouchable() {
            var dept = createDepartment(a, "BIO");
            var subject = createSubject(a, "BI101", dept.get("id"));
            var hallA = post(a, "/api/v1/halls", hall("BIOHALL", 40)).getBody();

            for (String path : List.of("/api/v1/departments/" + dept.get("id"), "/api/v1/subjects/" + subject.get("id"),
                    "/api/v1/halls/" + hallA.get("id"))) {
                assertThat(data().get(path, b.accessToken(), Map.class).getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(data().send(DELETE, path, b.accessToken(), null, Map.class).getStatusCode()).as(path)
                        .isEqualTo(HttpStatus.NOT_FOUND);
            }
            var update = data().send(PUT, "/api/v1/departments/" + dept.get("id"), b.accessToken(),
                    Map.of("code", "BIO", "name", "Hijacked", "active", true, "version", 0), Map.class);
            assertThat(update.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(data().get("/api/v1/departments", b.accessToken(), List.class).getBody()).isEmpty();
            assertThat(data().get("/api/v1/subjects", b.accessToken(), Map.class).getBody()).containsEntry("total", 0);
            assertThat(data().get("/api/v1/halls", b.accessToken(), List.class).getBody()).isEmpty();
        }

        @Test
        void subjectCannotUseAnotherCollegesDepartment() {
            var deptA = createDepartment(a, "ZOO");

            var r = post(b, "/api/v1/subjects", Map.of("code", "ZO101", "name", "Zoology", "departmentId", deptA.get("id"), "active", true));

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody()).containsEntry("code", "DEPARTMENT_NOT_FOUND");
        }

        @Test
        void databaseRejectsCrossCollegeReferencesEvenWithoutTheApi() {
            var deptA = createDepartment(a, "GEO");

            assertThatThrownBy(() -> jdbc.update(
                    "insert into invigilo.subject (college_id, department_id, code, name) values (?, ?, 'GE101', 'Geo')",
                    b.collegeId(), UUID.fromString((String) deptA.get("id"))))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("subject_department_fk");
        }
    }
}
