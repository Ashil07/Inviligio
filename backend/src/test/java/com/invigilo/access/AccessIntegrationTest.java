package com.invigilo.access;

import static com.invigilo.support.TestData.json;
import static com.invigilo.support.TestData.refreshCookie;
import static com.invigilo.support.TestData.withRefreshCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/** Invitations, accepting them (new and existing accounts) and exam-cell access control. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AccessIntegrationTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;

    TestData.Registered college;
    String deptId;

    @BeforeEach
    void setUp() {
        college = data().registerCollege();
        deptId = (String) data().send(POST, "/api/v1/departments", college.accessToken(),
                Map.of("code", "ME", "name", "Mechanical", "active", true), Map.class).getBody().get("id");
    }

    String createFaculty(String code, String email) {
        Map<String, Object> f = new HashMap<>();
        f.put("employeeCode", code);
        f.put("fullName", "Faculty " + code);
        f.put("departmentId", deptId);
        f.put("email", email);
        f.put("employmentType", "REGULAR");
        f.put("chiefEligible", false);
        f.put("dutyWeight", 1);
        f.put("active", true);
        var r = data().send(POST, "/api/v1/faculty", college.accessToken(), f, Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    ResponseEntity<Map> invite(String facultyId, List<String> roles) {
        return data().send(POST, "/api/v1/faculty/" + facultyId + "/access/invitation", college.accessToken(),
                Map.of("roles", roles), Map.class);
    }

    ResponseEntity<Map> accept(String token, String password) {
        return http.postForEntity("/api/v1/auth/invitations/accept", json(Map.of("token", token, "password", password)), Map.class);
    }

    String email() {
        return "inv." + TestData.unique().toLowerCase() + "@college.example.test";
    }

    @Test
    void newFacultyAcceptsInvitationAndSignsIn() {
        String email = email();
        String facultyId = createFaculty("F1", email);

        var issued = invite(facultyId, List.of("HOD"));
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = (String) issued.getBody().get("token");
        assertThat((Map<String, Object>) issued.getBody().get("access")).containsEntry("status", "INVITED");

        var preview = http.postForEntity("/api/v1/auth/invitations/preview", json(Map.of("token", token)), Map.class).getBody();
        assertThat(preview).containsEntry("email", email).containsEntry("existingAccount", false)
                .containsEntry("facultyName", "Faculty F1").containsEntry("roles", List.of("HOD", "FACULTY"));

        var accepted = accept(token, "faculty-password");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshCookie(accepted)).isNotBlank();
        var session = (Map<String, Object>) accepted.getBody().get("session");
        assertThat(session).containsEntry("facultyId", facultyId).containsEntry("roles", List.of("HOD", "FACULTY"));

        var login = http.postForEntity("/api/v1/auth/login", json(Map.of("email", email, "password", "faculty-password")), Map.class);
        assertThat(login.getBody()).containsEntry("status", "AUTHENTICATED");

        var access = data().get("/api/v1/faculty/" + facultyId + "/access", college.accessToken(), Map.class).getBody();
        assertThat(access).containsEntry("status", "ACTIVE").containsEntry("loginEmail", email);
    }

    @Test
    void invitationsAreSingleUseAndCanBeRevokedOrReplaced() {
        String facultyId = createFaculty("F2", email());
        String first = (String) invite(facultyId, List.of()).getBody().get("token");
        String second = (String) invite(facultyId, List.of()).getBody().get("token");

        assertThat(accept(first, "password-one").getBody()).containsEntry("code", "INVITATION_INVALID");

        data().send(DELETE, "/api/v1/faculty/" + facultyId + "/access/invitation", college.accessToken(), null, Map.class);
        assertThat(accept(second, "password-two").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String third = (String) invite(facultyId, List.of()).getBody().get("token");
        assertThat(accept(third, "password-three").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accept(third, "password-three").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(invite(facultyId, List.of()).getBody()).containsEntry("code", "ALREADY_HAS_ACCESS");
    }

    @Test
    void expiredInvitationIsRefused() {
        String facultyId = createFaculty("F3", email());
        String token = (String) invite(facultyId, List.of()).getBody().get("token");
        jdbc.update("update invigilo.invitation set expires_at = now() - interval '1 minute' where faculty_id = ?::uuid", facultyId);

        assertThat(accept(token, "whatever-password").getBody()).containsEntry("code", "INVITATION_INVALID");
    }

    @Test
    void existingAccountMustProveItsPassword() {
        // The registering admin is also a faculty member of the college: invite their own email.
        String facultyId = createFaculty("F4", college.email());
        String token = (String) invite(facultyId, List.of()).getBody().get("token");

        var preview = http.postForEntity("/api/v1/auth/invitations/preview", json(Map.of("token", token)), Map.class).getBody();
        assertThat(preview).containsEntry("existingAccount", true);
        assertThat(accept(token, "not-my-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var accepted = accept(token, TestData.PASSWORD);
        var session = (Map<String, Object>) accepted.getBody().get("session");
        assertThat(session).containsEntry("membershipId", college.membershipId().toString())
                .containsEntry("facultyId", facultyId)
                .containsEntry("roles", List.of("EXAM_CELL_ADMIN", "FACULTY"));
    }

    @Test
    void invitingRequiresAnActiveFacultyWithEmail() {
        String noEmail = createFaculty("F5", null);
        assertThat(invite(noEmail, List.of()).getBody()).containsEntry("code", "FACULTY_EMAIL_REQUIRED");
    }

    @Test
    void examCellControlsRolesAndCanDisableAccess() {
        String email = email();
        String facultyId = createFaculty("F6", email);
        String token = (String) invite(facultyId, List.of()).getBody().get("token");
        var accepted = accept(token, "faculty-password");
        String refresh = refreshCookie(accepted);

        var promoted = data().send(PUT, "/api/v1/faculty/" + facultyId + "/access", college.accessToken(),
                Map.of("roles", List.of("HOD"), "active", true), Map.class);
        assertThat(promoted.getBody()).containsEntry("roles", List.of("HOD", "FACULTY"));

        var disabled = data().send(PUT, "/api/v1/faculty/" + facultyId + "/access", college.accessToken(),
                Map.of("roles", List.of("HOD"), "active", false), Map.class);
        assertThat(disabled.getBody()).containsEntry("status", "DISABLED");
        assertThat(http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(refresh), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/v1/auth/login", json(Map.of("email", email, "password", "faculty-password")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A faculty member with a login cannot be deleted.
        var delete = data().send(DELETE, "/api/v1/faculty/" + facultyId, college.accessToken(), null, Map.class);
        assertThat(delete.getBody()).containsEntry("code", "FACULTY_IN_USE");
    }

    @Test
    void adminCannotLockThemselvesOut() {
        String facultyId = createFaculty("F7", college.email());
        accept((String) invite(facultyId, List.of()).getBody().get("token"), TestData.PASSWORD);

        var selfDemote = data().send(PUT, "/api/v1/faculty/" + facultyId + "/access", college.accessToken(),
                Map.of("roles", List.of(), "active", true), Map.class);

        assertThat(selfDemote.getBody()).containsEntry("code", "CANNOT_CHANGE_OWN_ACCESS");
    }

    @Test
    void lastAdminCannotBeRemovedByAnotherAdmin() {
        // A second admin (via invitation) tries to demote the only other admin after that one is disabled... simplest
        // form of the rule: with exactly one active admin, removing its admin role is refused.
        String email = email();
        String facultyId = createFaculty("F8", email);
        accept((String) invite(facultyId, List.of("EXAM_CELL_ADMIN")).getBody().get("token"), "second-admin-pw");
        // Disable the original admin's admin role through the second admin.
        var second = http.postForEntity("/api/v1/auth/login", json(Map.of("email", email, "password", "second-admin-pw")), Map.class);
        String secondToken = (String) ((Map<String, Object>) second.getBody().get("auth")).get("accessToken");
        String originalFaculty = createFaculty("F9", college.email());
        accept((String) invite(originalFaculty, List.of("EXAM_CELL_ADMIN")).getBody().get("token"), TestData.PASSWORD);

        var demoteOriginal = data().send(PUT, "/api/v1/faculty/" + originalFaculty + "/access", secondToken,
                Map.of("roles", List.of(), "active", true), Map.class);
        assertThat(demoteOriginal.getStatusCode()).isEqualTo(HttpStatus.OK); // another admin remains

        var demoteSelfViaOther = data().send(PUT, "/api/v1/faculty/" + facultyId + "/access", college.accessToken(),
                Map.of("roles", List.of(), "active", true), Map.class);
        // The original admin's token still says EXAM_CELL_ADMIN for up to 15 minutes, but the rule counts current DB state.
        assertThat(demoteSelfViaOther.getBody()).containsEntry("code", "LAST_ADMIN");
    }

    @Test
    void invitationsOfOneCollegeCannotBeManagedByAnother() {
        String facultyId = createFaculty("F10", email());
        var other = data().registerCollege();

        var r = data().send(POST, "/api/v1/faculty/" + facultyId + "/access/invitation", other.accessToken(), Map.of("roles", List.of()), Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
