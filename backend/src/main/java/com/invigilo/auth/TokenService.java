package com.invigilo.auth;

import com.invigilo.config.SecurityProperties;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.tenant.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Issues short-lived HS256 access tokens for one college membership. */
@Component
class TokenService {

    record AccessToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;

    TokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    AccessToken issue(CollegeMembership membership) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(membership.getUser().getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(CurrentUser.CLAIM_COLLEGE, membership.getCollegeId().toString())
                .claim(CurrentUser.CLAIM_MEMBERSHIP, membership.getId().toString())
                .claim(CurrentUser.CLAIM_ROLES, membership.getRoles().stream().sorted().map(Enum::name).toList())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }
}
