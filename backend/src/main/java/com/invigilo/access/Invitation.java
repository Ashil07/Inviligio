package com.invigilo.access;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.faculty.Faculty;
import com.invigilo.identity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One-time link that lets a faculty member create (or link) a login. Only the token hash is stored. */
@Entity
@Table(name = "invitation")
public class Invitation extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "faculty_id", nullable = false, updatable = false)
    private Faculty faculty;

    @Column(nullable = false, updatable = false)
    private String email;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private List<String> roles;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected Invitation() {}

    Invitation(Faculty faculty, String email, Set<Role> roles, String tokenHash, Instant expiresAt, UUID createdBy) {
        this.faculty = faculty;
        this.email = email;
        this.roles = roles.stream().sorted().map(Enum::name).toList();
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    boolean isPending(Instant now) {
        return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    void accept(Instant at) {
        this.acceptedAt = at;
    }

    void revoke(Instant at) {
        this.revokedAt = at;
    }

    public Faculty getFaculty() { return faculty; }
    public String getEmail() { return email; }
    public Instant getExpiresAt() { return expiresAt; }

    public Set<Role> getRoles() {
        Set<Role> set = EnumSet.noneOf(Role.class);
        roles.forEach(r -> set.add(Role.valueOf(r)));
        return set;
    }
}
