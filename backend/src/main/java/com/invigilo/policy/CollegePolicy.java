package com.invigilo.policy;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.identity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One version of a college's policy. Immutable once written: a change is a new version. */
@Entity
@Immutable
@Table(name = "college_policy")
public class CollegePolicy extends TenantScopedEntity {

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, updatable = false)
    private PolicySettings settings;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    protected CollegePolicy() {}

    CollegePolicy(int policyVersion, PolicySettings settings, AppUser createdBy) {
        this.policyVersion = policyVersion;
        this.settings = settings;
        this.createdBy = createdBy;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public PolicySettings getSettings() {
        return settings;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }
}
