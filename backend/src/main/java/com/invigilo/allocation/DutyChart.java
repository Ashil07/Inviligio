package com.invigilo.allocation;

import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A version of a period's duty chart. DRAFT is replaced by every generation and edited in M9;
 * PUBLISHED versions are immutable (M10). Records the policy version and input fingerprint it was
 * built from, so later rule or schedule changes are visible, never silently applied.
 */
@Entity
@Table(name = "duty_chart")
public class DutyChart extends TenantScopedEntity {

    public enum Status { DRAFT, PUBLISHED, SUPERSEDED }

    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;

    @Column(name = "chart_version", nullable = false, updatable = false)
    private int chartVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "policy_id", updatable = false)
    private UUID policyId;

    @Column(name = "allocation_run_id", updatable = false)
    private UUID allocationRunId;

    @Column(name = "input_hash", nullable = false, updatable = false)
    private String inputHash;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected DutyChart() {}

    DutyChart(UUID periodId, int chartVersion, UUID policyId, UUID allocationRunId, String inputHash, UUID createdBy) {
        this.periodId = periodId;
        this.chartVersion = chartVersion;
        this.policyId = policyId;
        this.allocationRunId = allocationRunId;
        this.inputHash = inputHash;
        this.createdBy = createdBy;
    }

    public UUID getPeriodId() { return periodId; }
    public int getChartVersion() { return chartVersion; }
    public Status getStatus() { return status; }
    public UUID getPolicyId() { return policyId; }
    public UUID getAllocationRunId() { return allocationRunId; }
    public String getInputHash() { return inputHash; }
    public Instant getPublishedAt() { return publishedAt; }
    public UUID getCreatedBy() { return createdBy; }
}
