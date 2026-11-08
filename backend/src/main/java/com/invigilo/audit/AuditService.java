package com.invigilo.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invigilo.tenant.CurrentUser;
import com.invigilo.tenant.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records who changed what. Must be called inside the transaction that makes the change
 * ({@code MANDATORY}), so the change and its audit entry commit or roll back together.
 */
@Service
public class AuditService {

    private final AuditRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** {@code before}/{@code after} are serialised to JSON (DTOs or records, never entities). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, UUID entityId, Object before, Object after, String reason) {
        save(null, CurrentUser.find().map(CurrentUser::userId).orElse(null), action, entityType, entityId, before, after, reason);
    }

    /**
     * For system-context flows (no tenant yet, e.g. accepting an invitation): the college and actor
     * are given explicitly.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordForCollege(UUID collegeId, UUID actorUserId, String action, String entityType, UUID entityId,
                                 Object before, Object after, String reason) {
        if (!TenantContext.isSystem()) {
            throw new IllegalStateException("recordForCollege is only for the system context; use record()");
        }
        save(collegeId, actorUserId, action, entityType, entityId, before, after, reason);
    }

    private void save(UUID collegeId, UUID actorUserId, String action, String entityType, UUID entityId,
                      Object before, Object after, String reason) {
        repository.save(new AuditEntry(
                collegeId,
                actorUserId,
                action,
                entityType,
                entityId,
                before == null ? null : objectMapper.valueToTree(before),
                after == null ? null : objectMapper.valueToTree(after),
                reason,
                clock.instant()));
    }
}
