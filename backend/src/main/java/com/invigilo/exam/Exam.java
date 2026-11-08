package com.invigilo.exam;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.subject.Subject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A subject examined in a session. Its department is the subject's department. */
@Entity
@Table(name = "exam")
public class Exam extends TenantScopedEntity {

    public enum Type { THEORY, PRACTICAL }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private ExamSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false, updatable = false)
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false)
    private Type type = Type.THEORY;

    /** Students registered for the exam; optional, used to warn about unseated students. */
    @Column(name = "registered_students")
    private Integer registeredStudents;

    private String notes;

    protected Exam() {}

    Exam(ExamSession session, Subject subject, Type type, Integer registeredStudents, String notes) {
        this.session = session;
        this.subject = subject;
        update(type, registeredStudents, notes);
    }

    void update(Type type, Integer registeredStudents, String notes) {
        this.type = type;
        this.registeredStudents = registeredStudents;
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
    }

    public ExamSession getSession() { return session; }
    public Subject getSubject() { return subject; }
    public Type getType() { return type; }
    public Integer getRegisteredStudents() { return registeredStudents; }
    public String getNotes() { return notes; }
}
