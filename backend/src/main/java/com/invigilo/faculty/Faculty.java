package com.invigilo.faculty;

import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.department.Department;
import com.invigilo.subject.Subject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A college's staff record for invigilation. Exists independently of any login; a login is linked
 * through {@code college_membership.faculty_id} when the faculty member accepts an invitation.
 */
@Entity
@Table(name = "faculty")
public class Faculty extends TenantScopedEntity {

    public enum EmploymentType { REGULAR, VISITING, GUEST, NON_TEACHING }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(name = "employee_code", nullable = false)
    private String employeeCode;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String email;
    private String phone;
    private String designation;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false)
    private EmploymentType employmentType = EmploymentType.REGULAR;

    @Column(name = "joined_on")
    private LocalDate joinedOn;

    @Column(name = "chief_eligible", nullable = false)
    private boolean chiefEligible;

    /** 1.00 = normal share of duties; e.g. 0.50 for faculty with a reduced invigilation load. */
    @Column(name = "duty_weight", nullable = false, precision = 3, scale = 2)
    private BigDecimal dutyWeight = BigDecimal.ONE;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "faculty", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<FacultySubject> subjects = new HashSet<>();

    protected Faculty() {}

    Faculty(Department department, FacultyService.FacultyInput input) {
        update(department, input);
    }

    void update(Department department, FacultyService.FacultyInput input) {
        this.department = department;
        this.employeeCode = Codes.normalize(input.employeeCode());
        this.fullName = input.fullName().trim();
        this.email = blankToNull(input.email()) == null ? null : input.email().trim().toLowerCase(Locale.ROOT);
        this.phone = blankToNull(input.phone());
        this.designation = blankToNull(input.designation());
        this.employmentType = input.employmentType();
        this.joinedOn = input.joinedOn();
        this.chiefEligible = input.chiefEligible();
        // Fixed scale so 0.5 and 0.50 are the same value (stored as numeric(3,2)).
        this.dutyWeight = input.dutyWeight().setScale(2, java.math.RoundingMode.HALF_UP);
        this.active = input.active();
    }

    /**
     * Makes the taught subjects exactly {@code wanted}, touching only the differences (removing and
     * re-adding the same pair in one flush would collide on the unique constraint).
     */
    void replaceSubjects(Collection<Subject> wanted) {
        Set<UUID> wantedIds = wanted.stream().map(Subject::getId).collect(Collectors.toSet());
        subjects.removeIf(fs -> !wantedIds.contains(fs.getSubject().getId()));
        Set<UUID> present = subjects.stream().map(fs -> fs.getSubject().getId()).collect(Collectors.toSet());
        for (Subject s : wanted) {
            if (!present.contains(s.getId())) {
                subjects.add(new FacultySubject(this, s));
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Department getDepartment() { return department; }
    public String getEmployeeCode() { return employeeCode; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getDesignation() { return designation; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public LocalDate getJoinedOn() { return joinedOn; }
    public boolean isChiefEligible() { return chiefEligible; }
    public BigDecimal getDutyWeight() { return dutyWeight; }
    public boolean isActive() { return active; }
    public Set<FacultySubject> getSubjects() { return subjects; }
}
