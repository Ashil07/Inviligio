package com.invigilo.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invigilo.college.CollegeRepository;
import com.invigilo.identity.AppUserRepository;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.identity.CollegeMembershipRepository;
import com.invigilo.identity.Role;
import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Proves that tenant-owned rows of one college are invisible to another. Copy the pattern for every
 * new tenant resource: create data in college A and B, then read/update/delete/reference B's data as A.
 */
class TenantIsolationIntegrationTest extends IntegrationTest {

    @Autowired CollegeMembershipRepository memberships;
    @Autowired CollegeRepository colleges;
    @Autowired AppUserRepository users;
    @Autowired EntityManager em;

    TestData.Registered a;
    TestData.Registered b;

    @BeforeEach
    void twoColleges() {
        a = data().registerCollege();
        b = data().registerCollege();
    }

    @Test
    void findByIdOfAnotherCollegesRowIsEmpty() {
        assertThat(tenants.asTenant(a.collegeId(), () -> memberships.findById(b.membershipId()))).isEmpty();
        assertThat(tenants.asTenant(a.collegeId(), () -> memberships.findById(a.membershipId()))).isPresent();
    }

    @Test
    void queriesOnlyReturnOwnRows() {
        List<CollegeMembership> visible = tenants.asTenant(a.collegeId(), () -> memberships.findAll());

        assertThat(visible).isNotEmpty().allSatisfy(m -> assertThat(m.getCollegeId()).isEqualTo(a.collegeId()));
        assertThat(tenants.asTenant(a.collegeId(), () -> memberships.findWithDetailsById(b.membershipId()))).isEmpty();
        assertThat(tenants.asTenant(a.collegeId(), () -> memberships.findActiveByUserId(b.userId()))).isEmpty();
        Long jpqlCount = tenants.asTenant(a.collegeId(), () -> em
                .createQuery("select count(m) from CollegeMembership m where m.id = :id", Long.class)
                .setParameter("id", b.membershipId())
                .getSingleResult());
        assertThat(jpqlCount).isZero();
    }

    @Test
    void anotherCollegesRowCannotBeDeleted() {
        tenants.asTenant(a.collegeId(), () -> {
            memberships.deleteById(b.membershipId());
            return null;
        });

        assertThat(tenants.asTenant(b.collegeId(), () -> memberships.findById(b.membershipId()))).isPresent();
    }

    @Test
    void codeWithoutTenantSeesNothing() {
        assertThat(tenants.withoutTenant(() -> memberships.findAll())).isEmpty();
        assertThat(tenants.withoutTenant(() -> memberships.findById(a.membershipId()))).isEmpty();
    }

    @Test
    void rowsForAnotherCollegeCannotBeCreatedFromATenantContext() {
        assertThatThrownBy(() -> tenants.asTenant(a.collegeId(), () -> {
            var collegeB = colleges.findById(b.collegeId()).orElseThrow();
            var user = users.findById(a.userId()).orElseThrow();
            return memberships.save(CollegeMembership.forCollege(collegeB, user, EnumSet.of(Role.FACULTY)));
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void systemContextSeesAllColleges() {
        List<CollegeMembership> all = tenants.asSystem(() -> memberships.findAll());
        assertThat(all).extracting(CollegeMembership::getCollegeId).contains(a.collegeId(), b.collegeId());
    }

    @Test
    void tenantComesFromTheTokenNotFromTheData() {
        // A correctly signed token claiming college B but pointing at college A's membership:
        // A's membership is invisible inside tenant B, so the session is rejected.
        String mixed = sign(a.userId(), b.collegeId(), a.membershipId());

        var response = data().get("/api/v1/auth/me", mixed, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "SESSION_INVALID");
    }

    @Test
    void meReturnsOnlyTheCallersCollege() {
        var response = data().get("/api/v1/auth/me", a.accessToken(), Map.class);

        @SuppressWarnings("unchecked")
        var college = (Map<String, Object>) response.getBody().get("college");
        assertThat(college).containsEntry("id", a.collegeId().toString());
    }

    @Test
    void tenantContextIsClearedAfterEachRequest() {
        data().get("/api/v1/auth/me", a.accessToken(), Map.class);
        // The test thread never had a tenant; request threads must not leak theirs into pooled threads.
        assertThat(TenantContext.currentCollegeId()).isEmpty();
    }

    private static String sign(UUID userId, UUID collegeId, UUID membershipId) {
        var key = new SecretKeySpec(
                "test-only-secret-key-that-is-at-least-32-chars".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("invigilo").subject(userId.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("cid", collegeId.toString())
                .claim("mid", membershipId.toString())
                .claim("roles", List.of("EXAM_CELL_ADMIN"))
                .build();
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
