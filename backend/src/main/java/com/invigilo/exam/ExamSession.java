package com.invigilo.exam;

import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

/** One sitting: a date and a time range. The label (FN/AN) is for people; overlap uses the times. */
@Entity
@Table(name = "exam_session")
public class ExamSession extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false, updatable = false)
    private ExamPeriod period;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(nullable = false)
    private String label;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    protected ExamSession() {}

    ExamSession(ExamPeriod period, LocalDate date, String label, LocalTime start, LocalTime end) {
        this.period = period;
        update(date, label, start, end);
    }

    void update(LocalDate date, String label, LocalTime start, LocalTime end) {
        this.sessionDate = date;
        this.label = normalizeLabel(label);
        this.startTime = start;
        this.endTime = end;
    }

    static String normalizeLabel(String label) {
        return label.trim().toUpperCase(Locale.ROOT);
    }

    public ExamPeriod getPeriod() { return period; }
    public LocalDate getSessionDate() { return sessionDate; }
    public String getLabel() { return label; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
}
