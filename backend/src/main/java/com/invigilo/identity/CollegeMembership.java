package com.invigilo.identity;

import com.invigilo.college.College;
import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** A user's access to one college, with the roles they hold there. Tenant-owned. */
@Entity
@Table(name = "college_membership")
public class CollegeMembership extends TenantScopedEntity {

    public enum Status { ACTIVE, DISABLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    /** Read-only view of the tenant column as an association (the column is owned by {@code collegeId}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id", insertable = false, updatable = false)
    private College college;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    /** The faculty record this login belongs to, if any (set when a faculty invitation is accepted). */
    @Column(name = "faculty_id")
    private java.util.UUID facultyId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "college_membership_role", joinColumns = @JoinColumn(name = "membership_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles = new HashSet<>();

    protected CollegeMembership() {}

    /** Creates a membership for a college that is not the current tenant (system context only). */
    public static CollegeMembership forCollege(College college, AppUser user, Set<Role> roles) {
        CollegeMembership m = new CollegeMembership();
        m.assignCollege(college.getId());
        m.college = college;
        m.user = user;
        m.roles.addAll(roles);
        return m;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** Links this login to a faculty record and grants {@code roles} in addition to existing ones. */
    public void linkFaculty(java.util.UUID facultyId, Set<Role> roles) {
        this.facultyId = facultyId;
        this.roles.addAll(roles);
    }

    /** Replaces roles and status (exam-cell access management). */
    public void updateAccess(Set<Role> roles, boolean active) {
        this.roles.clear();
        this.roles.addAll(roles);
        this.status = active ? Status.ACTIVE : Status.DISABLED;
    }

    public java.util.UUID getFacultyId() {
        return facultyId;
    }

    public AppUser getUser() {
        return user;
    }

    public College getCollege() {
        return college;
    }

    public Status getStatus() {
        return status;
    }

    public Set<Role> getRoles() {
        return roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }
}
