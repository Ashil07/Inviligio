package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult;
import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.exam.SessionHall;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Positions of a session hall that a chart could not fill, with why and what would help. */
@Entity
@Table(name = "unfilled_position")
public class UnfilledPosition extends TenantScopedEntity {

    /** Stored explanation: faculty excluded per reason and concrete suggestions. */
    public record Reasoning(int totalFaculty, Map<String, Integer> eliminated, List<String> suggestions) {}

    @Column(name = "chart_id", nullable = false, updatable = false)
    private UUID chartId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_hall_id", nullable = false, updatable = false)
    private SessionHall sessionHall;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AllocationResult.Role role;

    @Column(nullable = false)
    private int missing;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Reasoning reason;

    protected UnfilledPosition() {}

    UnfilledPosition(UUID chartId, SessionHall sessionHall, AllocationResult.Role role, int missing, Reasoning reason) {
        this.chartId = chartId;
        this.sessionHall = sessionHall;
        this.role = role;
        this.missing = missing;
        this.reason = reason;
    }

    public UUID getChartId() { return chartId; }
    public SessionHall getSessionHall() { return sessionHall; }
    public AllocationResult.Role getRole() { return role; }
    public int getMissing() { return missing; }
    public Reasoning getReason() { return reason; }
}
