package com.invigilo.exam;

import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** An examination season, e.g. "November 2026 end-semester". Sessions fall within its dates. */
@Entity
@Table(name = "exam_period")
public class ExamPeriod extends TenantScopedEntity {

    /** PLANNING and ALLOCATING are editable; PUBLISHED and CLOSED are set by later milestones. */
    public enum Status { PLANNING, ALLOCATING, PUBLISHED, CLOSED }

    @Column(nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PLANNING;

    protected ExamPeriod() {}

    ExamPeriod(String name, LocalDate startDate, LocalDate endDate) {
        update(name, startDate, endDate);
    }

    void update(String name, LocalDate startDate, LocalDate endDate) {
        this.name = name.trim();
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public boolean isEditable() {
        return status == Status.PLANNING || status == Status.ALLOCATING;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public String getName() { return name; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public Status getStatus() { return status; }
}
