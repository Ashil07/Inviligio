package com.invigilo.subject;

import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.department.Department;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A subject examined by the college. Its department matters for the own-subject and
 * own-department rules. The DB foreign key is (college_id, department_id), so the department
 * always belongs to the same college.
 */
@Entity
@Table(name = "subject")
public class Subject extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String programme;

    private Integer semester;

    @Column(nullable = false)
    private boolean active = true;

    protected Subject() {}

    Subject(Department department, SubjectService.SubjectInput input) {
        update(department, input);
    }

    void update(Department department, SubjectService.SubjectInput input) {
        this.department = department;
        this.code = Codes.normalize(input.code());
        this.name = input.name().trim();
        this.programme = input.programme() == null || input.programme().isBlank() ? null : input.programme().trim();
        this.semester = input.semester();
        this.active = input.active();
    }

    public Department getDepartment() { return department; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getProgramme() { return programme; }
    public Integer getSemester() { return semester; }
    public boolean isActive() { return active; }
}
