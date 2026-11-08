package com.invigilo.common.persistence;

import com.invigilo.tenant.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base for every row owned by a college.
 *
 * <p>{@code @TenantId} makes Hibernate add {@code college_id = <current tenant>} to every
 * HQL/Criteria query and id lookup, and fill the column on insert from
 * {@link TenantContext}. Native SQL is NOT filtered — include {@code college_id} explicitly.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @TenantId
    @Column(name = "college_id", nullable = false, updatable = false)
    private UUID collegeId;

    public UUID getCollegeId() {
        return collegeId;
    }

    /**
     * Only for system-context flows that create rows for a college other than the (absent)
     * current tenant, e.g. college registration. Inside a tenant context Hibernate rejects a value
     * that differs from the current tenant.
     */
    protected void assignCollege(UUID collegeId) {
        if (!TenantContext.isSystem()) {
            throw new IllegalStateException("assignCollege is only allowed in the system context");
        }
        this.collegeId = collegeId;
    }
}
