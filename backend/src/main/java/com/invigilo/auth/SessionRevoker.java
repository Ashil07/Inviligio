package com.invigilo.auth;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends every refresh session of a membership (e.g. when the exam cell disables someone's access).
 * Access tokens already issued stay valid until they expire (at most the access-token TTL).
 */
@Component
public class SessionRevoker {

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    SessionRevoker(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(UUID membershipId) {
        refreshTokens.revokeAllForMembership(membershipId, clock.instant());
    }
}
