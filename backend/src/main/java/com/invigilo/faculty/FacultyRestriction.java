package com.invigilo.faculty;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.department.Department;
import com.invigilo.hall.Hall;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A hard, per-person rule for allocation (always enforced, regardless of college policy). */
@Entity
@Table(name = "faculty_restriction")
public class FacultyRestriction extends TenantScopedEntity {

    public enum Type {
        /** Never assign to {@code hall}. */
        EXCLUDE_HALL,
        /** Never assign to exams conducted by {@code department}. */
        EXCLUDE_DEPARTMENT_EXAMS,
        /** Never assign as chief invigilator. */
        NOT_CHIEF,
        /** At most {@code maxValue} duties on any day (tighter than the college policy). */
        MAX_DUTIES_PER_DAY,
        /** At most {@code maxValue} duties in an examination period. */
        MAX_DUTIES_PER_PERIOD
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "faculty_id", nullable = false, updatable = false)
    private Faculty faculty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hall_id", updatable = false)
    private Hall hall;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", updatable = false)
    private Department department;

    @Column(name = "max_value", updatable = false)
    private Integer maxValue;

    private String reason;

    protected FacultyRestriction() {}

    FacultyRestriction(Faculty faculty, Type type, Hall hall, Department department, Integer maxValue, String reason) {
        this.faculty = faculty;
        this.type = type;
        this.hall = hall;
        this.department = department;
        this.maxValue = maxValue;
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public Faculty getFaculty() { return faculty; }
    public Type getType() { return type; }
    public Hall getHall() { return hall; }
    public Department getDepartment() { return department; }
    public Integer getMaxValue() { return maxValue; }
    public String getReason() { return reason; }
}
