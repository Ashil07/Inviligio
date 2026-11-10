package com.invigilo.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered. Append-only: never update or delete entries. */
public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {

    List<AuditEntry> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId);

    long countByEntityTypeAndEntityIdAndActionInAndOccurredAtAfter(String entityType, UUID entityId,
                                                                    java.util.Collection<String> actions, java.time.Instant after);
}
