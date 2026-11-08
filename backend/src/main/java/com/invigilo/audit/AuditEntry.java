package com.invigilo.audit;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** Append-only audit record. Tenant-owned; {@code college_id} is stamped from the tenant context. */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @TenantId
    @Column(name = "college_id", nullable = false, updatable = false)
    private UUID collegeId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", updatable = false)
    private JsonNode beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", updatable = false)
    private JsonNode afterState;

    @Column(updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEntry() {}

    /** {@code collegeId} null = stamped from the tenant context; set it only in the system context. */
    AuditEntry(UUID collegeId, UUID actorUserId, String action, String entityType, UUID entityId,
               JsonNode beforeState, JsonNode afterState, String reason, Instant occurredAt) {
        this.collegeId = collegeId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getCollegeId() { return collegeId; }
    public UUID getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public JsonNode getBeforeState() { return beforeState; }
    public JsonNode getAfterState() { return afterState; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
