package com.invigilo.auth;

import com.invigilo.common.security.Tokens;
import com.invigilo.config.SecurityProperties;
import com.invigilo.identity.CollegeMembership;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Opaque, rotating refresh tokens. Must be called inside a (system) transaction.
 *
 * <p>Reuse detection: presenting an already-rotated token revokes every refresh token of that
 * membership — a stolen token stops working as soon as either party uses it. Exception: within
 * {@link #ROTATION_GRACE} of its rotation the old token is accepted once more without rotating,
 * because two browser tabs refreshing at the same moment is normal, not an attack.
 */
@Component
class RefreshTokenService {

    static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

    /** Outcome of {@link #rotate}. {@code newToken} is null for a grace-period reuse. */
    record Rotation(CollegeMembership membership, String newToken) {}

    private final RefreshTokenRepository repository;
    private final SecurityProperties properties;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository repository, SecurityProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    String create(CollegeMembership membership) {
        String raw = newRawToken();
        repository.save(new RefreshToken(membership, hash(raw), clock.instant().plus(properties.refresh().ttl())));
        return raw;
    }

    /** @return the rotation, or {@code null} when the token is unknown, expired or revoked. */
    Rotation rotate(String raw) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHash(hash(raw)).orElse(null);
        if (current == null || current.isExpired(now)) {
            return null;
        }
        if (current.isRevoked()) {
            if (current.wasRotatedWithin(now, ROTATION_GRACE)) {
                return new Rotation(current.getMembership(), null);
            }
            repository.revokeAllForMembership(current.getMembership().getId(), now);
            return null;
        }
        String nextRaw = newRawToken();
        RefreshToken next = repository.save(
                new RefreshToken(current.getMembership(), hash(nextRaw), now.plus(properties.refresh().ttl())));
        current.revoke(now, next.getId());
        return new Rotation(current.getMembership(), nextRaw);
    }

    void revoke(String raw) {
        repository.findByTokenHash(hash(raw))
                .filter(t -> !t.isRevoked())
                .ifPresent(t -> t.revoke(clock.instant(), null));
    }

    void revokeAll(CollegeMembership membership) {
        repository.revokeAllForMembership(membership.getId(), clock.instant());
    }

    private String newRawToken() {
        return Tokens.newToken();
    }

    static String hash(String raw) {
        return Tokens.sha256Hex(raw);
    }
}
