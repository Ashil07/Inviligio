package com.invigilo.auth;

import com.invigilo.identity.CollegeMembership;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/** Server-side record of an issued refresh token. Only the SHA-256 hash of the token is stored. */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false, updatable = false)
    private CollegeMembership membership;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    RefreshToken(CollegeMembership membership, String tokenHash, Instant expiresAt) {
        this.membership = membership;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** True only for a token replaced by rotation (not logout/reuse revocation) less than {@code grace} ago. */
    boolean wasRotatedWithin(Instant now, java.time.Duration grace) {
        return revokedAt != null && replacedBy != null && now.isBefore(revokedAt.plus(grace));
    }

    void revoke(Instant at, UUID replacedBy) {
        this.revokedAt = at;
        this.replacedBy = replacedBy;
    }

    UUID getId() {
        return id;
    }

    CollegeMembership getMembership() {
        return membership;
    }
}
