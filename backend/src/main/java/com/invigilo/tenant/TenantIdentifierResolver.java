package com.invigilo.tenant;

import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate which college the current session belongs to.
 *
 * <ul>
 *   <li>Tenant context → that college id: queries are filtered, inserts are stamped.</li>
 *   <li>System context → {@link #SYSTEM} (a "root" tenant): no filtering; inserts must set
 *       {@code college_id} explicitly.</li>
 *   <li>Neither → {@link #NONE}: matches no rows and violates the FK on insert, so code that
 *       forgot to establish a tenant fails closed instead of seeing everything.</li>
 * </ul>
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    static final UUID SYSTEM = new UUID(0L, 0L);
    static final UUID NONE = new UUID(-1L, -1L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        if (TenantContext.isSystem()) {
            return SYSTEM;
        }
        return TenantContext.currentCollegeId().orElse(NONE);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return SYSTEM.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
