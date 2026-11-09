package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult;
import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One press of "Generate": inputs fingerprint, policy version used, outcome. */
@Entity
@Table(name = "allocation_run")
public class AllocationRun extends TenantScopedEntity {

    public enum Status { COMPLETED, FAILED }

    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;

    @Column(name = "policy_id", updatable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "input_hash", nullable = false, updatable = false)
    private String inputHash;

    private int positions;
    private int filled;

    @JdbcTypeCode(SqlTypes.JSON)
    private AllocationResult.Stats stats;

    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected AllocationRun() {}

    AllocationRun(UUID periodId, UUID policyId, String inputHash, Instant startedAt, UUID createdBy) {
        this.periodId = periodId;
        this.policyId = policyId;
        this.inputHash = inputHash;
        this.startedAt = startedAt;
        this.finishedAt = startedAt;
        this.createdBy = createdBy;
        this.status = Status.FAILED;
    }

    void completed(AllocationResult.Stats stats, Instant at) {
        this.status = Status.COMPLETED;
        this.stats = stats;
        this.positions = stats.positions();
        this.filled = stats.filled();
        this.finishedAt = at;
    }

    void failed(String error, Instant at) {
        this.status = Status.FAILED;
        this.error = error;
        this.finishedAt = at;
    }

    public UUID getPeriodId() { return periodId; }
    public UUID getPolicyId() { return policyId; }
    public Status getStatus() { return status; }
    public String getInputHash() { return inputHash; }
    public AllocationResult.Stats getStats() { return stats; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
