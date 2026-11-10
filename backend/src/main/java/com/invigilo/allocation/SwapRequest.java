package com.invigilo.allocation;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.faculty.Faculty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A faculty member offers one of their published duties to a colleague: as a handover
 * ({@code targetAssignment == null}) or in exchange for one of the colleague's duties (M13).
 * The status moves PENDING_RECIPIENT → PENDING_HOD → APPROVED, or ends REJECTED, CANCELLED or
 * INVALIDATED (the swap no longer fits the chart when it was to be applied).
 */
@Entity
@Table(name = "swap_request")
public class SwapRequest extends TenantScopedEntity {

    public enum Status { PENDING_RECIPIENT, PENDING_HOD, APPROVED, REJECTED, CANCELLED, INVALIDATED }

    static final Set<Status> OPEN = EnumSet.of(Status.PENDING_RECIPIENT, Status.PENDING_HOD);

    @Column(name = "chart_id", nullable = false, updatable = false)
    private UUID chartId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_assignment_id", nullable = false, updatable = false)
    private DutyAssignment requesterAssignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_faculty_id", nullable = false, updatable = false)
    private Faculty requester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_faculty_id", nullable = false, updatable = false)
    private Faculty target;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_assignment_id", updatable = false)
    private DutyAssignment targetAssignment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(updatable = false)
    private String reason;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "recipient_responded_at")
    private Instant recipientRespondedAt;

    @Column(name = "recipient_note")
    private String recipientNote;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "invalid_reason")
    private String invalidReason;

    @Column(name = "applied_at")
    private Instant appliedAt;

    protected SwapRequest() {}

    SwapRequest(DutyAssignment requesterAssignment, Faculty target, DutyAssignment targetAssignment, String reason,
                UUID requestedBy, Status status) {
        this.chartId = requesterAssignment.getChartId();
        this.requesterAssignment = requesterAssignment;
        this.requester = requesterAssignment.getFaculty();
        this.target = target;
        this.targetAssignment = targetAssignment;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.status = status;
    }

    boolean isOpen() {
        return OPEN.contains(status);
    }

    boolean isHandover() {
        return targetAssignment == null;
    }

    void recipientResponded(Status next, Instant at, String note) {
        this.status = next;
        this.recipientRespondedAt = at;
        this.recipientNote = note;
    }

    void reviewed(Status next, UUID reviewer, Instant at, String note) {
        this.status = next;
        this.reviewedBy = reviewer;
        this.reviewedAt = at;
        this.reviewNote = note;
    }

    void cancel() {
        this.status = Status.CANCELLED;
    }

    void applied(Instant at) {
        this.status = Status.APPROVED;
        this.appliedAt = at;
    }

    void invalidate(String why) {
        this.status = Status.INVALIDATED;
        this.invalidReason = why;
    }

    public UUID getChartId() { return chartId; }
    public DutyAssignment getRequesterAssignment() { return requesterAssignment; }
    public Faculty getRequester() { return requester; }
    public Faculty getTarget() { return target; }
    public DutyAssignment getTargetAssignment() { return targetAssignment; }
    public Status getStatus() { return status; }
    public String getReason() { return reason; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getRecipientRespondedAt() { return recipientRespondedAt; }
    public String getRecipientNote() { return recipientNote; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
    public String getInvalidReason() { return invalidReason; }
    public Instant getAppliedAt() { return appliedAt; }
}
