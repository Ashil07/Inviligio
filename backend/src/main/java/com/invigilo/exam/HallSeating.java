package com.invigilo.exam;

import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** How many students of one exam sit in one session hall. */
@Entity
@Table(name = "hall_seating")
public class HallSeating extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_hall_id", nullable = false, updatable = false)
    private SessionHall sessionHall;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false, updatable = false)
    private Exam exam;

    @Column(name = "student_count", nullable = false)
    private int studentCount;

    protected HallSeating() {}

    HallSeating(SessionHall sessionHall, Exam exam, int studentCount) {
        this.sessionHall = sessionHall;
        this.exam = exam;
        this.studentCount = studentCount;
    }

    void setStudentCount(int studentCount) {
        this.studentCount = studentCount;
    }

    public SessionHall getSessionHall() { return sessionHall; }
    public Exam getExam() { return exam; }
    public int getStudentCount() { return studentCount; }
}
