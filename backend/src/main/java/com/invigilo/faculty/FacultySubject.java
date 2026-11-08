package com.invigilo.faculty;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.subject.Subject;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** "Faculty X teaches subject Y". An entity (not a join table) so the row carries college_id. */
@Entity
@Table(name = "faculty_subject")
public class FacultySubject extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "faculty_id", nullable = false, updatable = false)
    private Faculty faculty;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false, updatable = false)
    private Subject subject;

    protected FacultySubject() {}

    FacultySubject(Faculty faculty, Subject subject) {
        this.faculty = faculty;
        this.subject = subject;
    }

    public Faculty getFaculty() { return faculty; }
    public Subject getSubject() { return subject; }
}
