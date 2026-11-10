package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult;
import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.exam.SessionHall;
import com.invigilo.faculty.Faculty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One faculty member on duty in one session hall of a chart. */
@Entity
@Table(name = "duty_assignment")
public class DutyAssignment extends TenantScopedEntity {

    public enum Source { AUTO, MANUAL, SWAP, SUBSTITUTE }

    public enum Status { ASSIGNED, COMPLETED, ABSENT, CANCELLED }

    @Column(name = "chart_id", nullable = false, updatable = false)
    private UUID chartId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_hall_id", nullable = false)
    private SessionHall sessionHall;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "faculty_id", nullable = false)
    private Faculty faculty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationResult.Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.AUTO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ASSIGNED;

    @JdbcTypeCode(SqlTypes.JSON)
    private AllocationResult.Explanation explanation;

    protected DutyAssignment() {}

    DutyAssignment(UUID chartId, SessionHall sessionHall, Faculty faculty, AllocationResult.Role role,
                   AllocationResult.Explanation explanation) {
        this.chartId = chartId;
        this.sessionHall = sessionHall;
        this.faculty = faculty;
        this.role = role;
        this.explanation = explanation;
    }

    /** A duty placed by hand in the chart editor; it has no engine explanation. */
    static DutyAssignment manual(UUID chartId, SessionHall sessionHall, Faculty faculty, AllocationResult.Role role) {
        DutyAssignment d = new DutyAssignment(chartId, sessionHall, faculty, role, null);
        d.source = Source.MANUAL;
        return d;
    }

    /** The same duty in another chart version (a new draft started from the published chart). */
    static DutyAssignment copyOf(DutyAssignment d, UUID chartId) {
        DutyAssignment copy = new DutyAssignment(chartId, d.sessionHall, d.faculty, d.role, d.explanation);
        copy.source = d.source;
        copy.status = d.status;
        return copy;
    }

    /** Gives this duty to someone else (chart editor). */
    void reassign(Faculty to) {
        this.faculty = to;
        this.source = Source.MANUAL;
        this.explanation = null;
    }

    /** Gives this duty to the other side of an approved swap. */
    void swapTo(Faculty to) {
        this.faculty = to;
        this.source = Source.SWAP;
        this.explanation = null;
    }

    public UUID getChartId() { return chartId; }
    public SessionHall getSessionHall() { return sessionHall; }
    public Faculty getFaculty() { return faculty; }
    public AllocationResult.Role getRole() { return role; }
    public Source getSource() { return source; }
    public Status getStatus() { return status; }
    public AllocationResult.Explanation getExplanation() { return explanation; }
}
