package com.invigilo.auth;

import static com.invigilo.support.TestData.PASSWORD;
import static com.invigilo.support.TestData.json;
import static com.invigilo.support.TestData.refreshCookie;
import static com.invigilo.support.TestData.refreshSetCookie;
import static com.invigilo.support.TestData.withRefreshCookie;
import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.college.College;
import com.invigilo.college.CollegeRepository;
import com.invigilo.identity.AppUserRepository;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.identity.CollegeMembershipRepository;
import com.invigilo.identity.Role;
import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@SuppressWarnings({"unchecked", "rawtypes"})
class AuthIntegrationTest extends IntegrationTest {

    @Autowired CollegeRepository colleges;
    @Autowired AppUserRepository users;
    @Autowired CollegeMembershipRepository memberships;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;

    @Nested
    class Registration {

        @Test
        void createsOnboardingCollegeWithAdminAndSignsIn() {
            var reg = data().registerCollege();

            var jwt = jwtDecoder.decode(reg.accessToken());
            assertThat(jwt.getSubject()).isEqualTo(reg.userId().toString());
            assertThat(jwt.getClaimAsString("cid")).isEqualTo(reg.collegeId().toString());
            assertThat(jwt.getClaimAsString("mid")).isEqualTo(reg.membershipId().toString());
            assertThat(jwt.getClaimAsStringList("roles")).containsExactly("EXAM_CELL_ADMIN");

            var me = data().get("/api/v1/auth/me", reg.accessToken(), Map.class);
            assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((Map<String, Object>) me.getBody().get("college"))
                    .containsEntry("status", "ONBOARDING")
                    .containsEntry("code", reg.collegeCode());
            assertThat(me.getBody().get("roles")).isEqualTo(List.of("EXAM_CELL_ADMIN"));
        }

        @Test
        void setsHardenedRefreshCookieAndStoresOnlyItsHash() {
            String suffix = TestData.unique();
            var response = http.postForEntity("/api/v1/auth/register-college", json(TestData.registration(suffix)), Map.class);

            String setCookie = refreshSetCookie(response);
            assertThat(setCookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth", "Secure");
            String raw = refreshCookie(response);
            Integer rawStored = jdbc.queryForObject(
                    "select count(*) from invigilo.refresh_token where token_hash = ?", Integer.class, raw);
            Integer hashStored = jdbc.queryForObject(
                    "select count(*) from invigilo.refresh_token where token_hash = ?", Integer.class,
                    RefreshTokenService.hash(raw));
            assertThat(rawStored).isZero();
            assertThat(hashStored).isEqualTo(1);
        }

        @Test
        void normalizesCodeAndEmailAndHashesPassword() {
            String suffix = TestData.unique();
            Map<String, Object> body = new HashMap<>(TestData.registration(suffix));
            body.put("collegeCode", "c" + suffix.toLowerCase());
            body.put("adminEmail", "Mixed." + suffix + "@Example.TEST");
            var response = http.postForEntity("/api/v1/auth/register-college", json(body), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            var user = tenants.asSystem(() -> users.findByEmail("mixed." + suffix.toLowerCase() + "@example.test")).orElseThrow();
            assertThat(user.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
            assertThat(tenants.asSystem(() -> colleges.existsByCode("C" + suffix))).isTrue();
        }

        @Test
        void rejectsDuplicateCollegeCode() {
            var first = data().registerCollege();
            Map<String, Object> body = new HashMap<>(TestData.registration(TestData.unique()));
            body.put("collegeCode", first.collegeCode().toLowerCase());

            var response = http.postForEntity("/api/v1/auth/register-college", json(body), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("code", "COLLEGE_CODE_TAKEN");
        }

        @Test
        void rejectsExistingEmail() {
            var first = data().registerCollege();
            Map<String, Object> body = new HashMap<>(TestData.registration(TestData.unique()));
            body.put("adminEmail", first.email().toUpperCase());

            var response = http.postForEntity("/api/v1/auth/register-college", json(body), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("code", "EMAIL_TAKEN");
        }

        @Test
        void validatesInput() {
            var response = http.postForEntity("/api/v1/auth/register-college", json(Map.of(
                    "collegeName", "", "collegeCode", "bad code!", "adminFullName", "A",
                    "adminEmail", "not-an-email", "password", "short")), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
            assertThat((Map<String, String>) response.getBody().get("fieldErrors"))
                    .containsOnlyKeys("collegeName", "collegeCode", "adminEmail", "password");
        }
    }

    @Nested
    class Login {

        @Test
        void succeedsWithCorrectPasswordCaseInsensitiveEmail() {
            var reg = data().registerCollege();

            var response = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", reg.email().toUpperCase(), "password", PASSWORD)), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "AUTHENTICATED");
            assertThat(refreshCookie(response)).isNotBlank();
        }

        @Test
        void wrongPasswordAndUnknownEmailGiveTheSameAnswer() {
            var reg = data().registerCollege();

            var wrongPassword = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", reg.email(), "password", "wrong-password")), Map.class);
            var unknownEmail = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", "nobody." + TestData.unique() + "@example.test", "password", PASSWORD)), Map.class);

            assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(wrongPassword.getBody().get("code")).isEqualTo(unknownEmail.getBody().get("code"))
                    .isEqualTo("INVALID_CREDENTIALS");
            assertThat(wrongPassword.getBody().get("detail")).isEqualTo(unknownEmail.getBody().get("detail"));
        }

        @Test
        void userInSeveralCollegesMustChooseAndCanOnlyChooseOwnColleges() {
            var a = data().registerCollege();
            var b = data().registerCollege();
            var stranger = data().registerCollege();
            addMembership(b.collegeId(), a.userId(), EnumSet.of(Role.FACULTY));

            var choose = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", a.email(), "password", PASSWORD)), Map.class);
            assertThat(choose.getBody()).containsEntry("status", "COLLEGE_SELECTION_REQUIRED");
            assertThat(refreshSetCookie(choose)).isNull();
            List<Map<String, Object>> options = (List<Map<String, Object>>) choose.getBody().get("colleges");
            assertThat(options).extracting(o -> o.get("id"))
                    .containsExactlyInAnyOrder(a.collegeId().toString(), b.collegeId().toString());

            var pickB = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", a.email(), "password", PASSWORD, "collegeId", b.collegeId())), Map.class);
            String token = (String) ((Map<String, Object>) pickB.getBody().get("auth")).get("accessToken");
            var jwt = jwtDecoder.decode(token);
            assertThat(jwt.getClaimAsString("cid")).isEqualTo(b.collegeId().toString());
            assertThat(jwt.getClaimAsStringList("roles")).containsExactly("FACULTY");

            var pickStranger = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", a.email(), "password", PASSWORD, "collegeId", stranger.collegeId())), Map.class);
            assertThat(pickStranger.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(pickStranger.getBody()).containsEntry("code", "NO_ACTIVE_MEMBERSHIP");
        }

        @Test
        void suspendedCollegeCannotBeSignedInTo() {
            var reg = data().registerCollege();
            jdbc.update("update invigilo.college set status = 'SUSPENDED' where id = ?", reg.collegeId());

            var response = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", reg.email(), "password", PASSWORD)), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).containsEntry("code", "NO_ACTIVE_MEMBERSHIP");
        }

        @Test
        void disabledUserIsRefused() {
            var reg = data().registerCollege();
            jdbc.update("update invigilo.app_user set status = 'DISABLED' where id = ?", reg.userId());

            var response = http.postForEntity("/api/v1/auth/login",
                    json(Map.of("email", reg.email(), "password", PASSWORD)), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).containsEntry("code", "ACCOUNT_DISABLED");
        }
    }

    @Nested
    class Refresh {

        @Test
        void rotatesTokenAndIssuesNewAccessToken() {
            var reg = data().registerCollege();

            var response = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((String) response.getBody().get("accessToken")).isNotBlank();
            assertThat(refreshCookie(response)).isNotBlank().isNotEqualTo(reg.refreshToken());
        }

        @Test
        void missingOrUnknownCookieIs401AndClearsCookie() {
            var none = http.postForEntity("/api/v1/auth/refresh", null, Map.class);
            var unknown = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie("not-a-real-token"), Map.class);

            assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(unknown.getBody()).containsEntry("code", "INVALID_REFRESH_TOKEN");
            assertThat(refreshSetCookie(unknown)).contains("Max-Age=0");
        }

        @Test
        void reuseWithinGraceWindowIsToleratedWithoutRotating() {
            var reg = data().registerCollege();
            http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);

            var secondTab = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);

            assertThat(secondTab.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(refreshSetCookie(secondTab)).isNull();
        }

        @Test
        void reuseAfterGraceWindowRevokesTheWholeFamily() {
            var reg = data().registerCollege();
            var rotated = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);
            String current = refreshCookie(rotated);

            clock.advance(RefreshTokenService.ROTATION_GRACE.plusSeconds(1));
            var replay = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);
            var legit = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(current), Map.class);

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(legit.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void expiredTokenIsRejected() {
            var reg = data().registerCollege();
            jdbc.update("update invigilo.refresh_token set expires_at = now() - interval '1 second' where token_hash = ?",
                    RefreshTokenService.hash(reg.refreshToken()));

            var response = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void disabledMembershipCannotRefresh() {
            var reg = data().registerCollege();
            jdbc.update("update invigilo.college_membership set status = 'DISABLED' where id = ?", reg.membershipId());

            var response = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    class Logout {

        @Test
        void revokesRefreshTokenAndClearsCookie() {
            var reg = data().registerCollege();

            var logout = http.postForEntity("/api/v1/auth/logout", withRefreshCookie(reg.refreshToken()), Void.class);
            var refresh = http.postForEntity("/api/v1/auth/refresh", withRefreshCookie(reg.refreshToken()), Map.class);

            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(refreshSetCookie(logout)).contains("Max-Age=0");
            assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void withoutCookieStillSucceeds() {
            assertThat(http.postForEntity("/api/v1/auth/logout", null, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    private void addMembership(UUID collegeId, UUID userId, EnumSet<Role> roles) {
        tenants.asSystem(() -> {
            College college = colleges.findById(collegeId).orElseThrow();
            return memberships.save(CollegeMembership.forCollege(college, users.findById(userId).orElseThrow(), roles));
        });
    }

    @Test
    void accessTokenExpiryFollowsConfiguredTtl() {
        var reg = data().registerCollege();
        var jwt = jwtDecoder.decode(reg.accessToken());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }
}
