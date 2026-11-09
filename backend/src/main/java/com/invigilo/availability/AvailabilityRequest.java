package com.invigilo.availability;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.exam.ExamSession;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * A faculty member is unavailable for whole days ({@code session == null}) or for one session.
 * Only APPROVED requests block allocation; PENDING ones are shown as warnings.
 */
@Entity
@Table(name = "availability_request")
public class AvailabilityRequest extends TenantScopedEntity {

    public enum Type { LEAVE, UNAVAILABLE }

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "faculty_id", nullable = false, updatable = false)
    private Faculty faculty;

    @Column(name = "from_date", nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false, updatable = false)
    private LocalDate toDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", updatable = false)
    private ExamSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Column(updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    protected AvailabilityRequest() {}

    AvailabilityRequest(Faculty faculty, LocalDate fromDate, LocalDate toDate, ExamSession session, Type type,
                        String reason, Status status, UUID requestedBy) {
        this.faculty = faculty;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.session = session;
        this.type = type;
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.status = status;
        this.requestedBy = requestedBy;
    }

    void decide(Status status, UUID reviewer, Instant at, String note) {
        this.status = status;
        this.reviewedBy = reviewer;
        this.reviewedAt = at;
        this.reviewNote = note == null || note.isBlank() ? null : note.trim();
    }

    boolean isActive() {
        return status == Status.PENDING || status == Status.APPROVED;
    }

    /** Whether this request makes the faculty member unavailable for {@code s}. */
    public boolean covers(ExamSession s) {
        if (session != null) return session.getId().equals(s.getId());
        return !s.getSessionDate().isBefore(fromDate) && !s.getSessionDate().isAfter(toDate);
    }

    public Faculty getFaculty() { return faculty; }
    public LocalDate getFromDate() { return fromDate; }
    public LocalDate getToDate() { return toDate; }
    public ExamSession getSession() { return session; }
    public Type getType() { return type; }
    public String getReason() { return reason; }
    public Status getStatus() { return status; }
    public UUID getRequestedBy() { return requestedBy; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
}
