package com.invigilo.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.invigilo.system.SystemStatusController;
import com.invigilo.system.SystemStatusService;
import com.invigilo.system.SystemStatusService.SystemStatus;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemStatusController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class SecurityConfigTest {

    private static final String SECRET = "test-only-secret-key-that-is-at-least-32-chars";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SystemStatusService statusService;

    @Test
    void systemStatusIsPublic() throws Exception {
        when(statusService.status()).thenReturn(new SystemStatus(
                "invigilo", Instant.now(), new SystemStatus.Database("UP", "1")));

        mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    void protectedEndpointWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/faculty")).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIsAuthenticated() throws Exception {
        // No controller is mapped yet, so an authenticated request falls through to 404 (not 401).
        mvc.perform(get("/api/v1/faculty").header("Authorization", "Bearer " + token(SECRET, "invigilo", 300)))
                .andExpect(status().isNotFound());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        String forged = token("a-completely-different-secret-key-of-32+chars", "invigilo", 300);
        mvc.perform(get("/api/v1/faculty").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/faculty").header("Authorization", "Bearer " + token(SECRET, "invigilo", -600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        mvc.perform(get("/api/v1/faculty").header("Authorization", "Bearer " + token(SECRET, "someone-else", 300)))
                .andExpect(status().isUnauthorized());
    }

    private static String token(String secret, String issuer, long ttlSeconds) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("00000000-0000-0000-0000-000000000001")
                .issuedAt(now.minusSeconds(Math.max(0, -ttlSeconds) + 1))
                .expiresAt(now.plusSeconds(ttlSeconds))
                .claim("cid", "00000000-0000-0000-0000-0000000000aa")
                .claim(SecurityConfig.ROLES_CLAIM, List.of("EXAM_CELL_ADMIN"))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
