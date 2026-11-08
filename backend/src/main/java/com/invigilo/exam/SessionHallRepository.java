package com.invigilo.exam;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface SessionHallRepository extends JpaRepository<SessionHall, UUID> {

    @EntityGraph(attributePaths = {"hall", "seating", "seating.exam"})
    List<SessionHall> findBySessionId(UUID sessionId);

    @EntityGraph(attributePaths = {"hall", "seating", "seating.exam"})
    List<SessionHall> findBySessionIdIn(Collection<UUID> sessionIds);

    boolean existsBySessionIdAndHallId(UUID sessionId, UUID hallId);

    long countByHallId(UUID hallId);

    /** Uses of a hall by other sessions (any period) on the same date whose times overlap. */
    @Query("""
            select sh from SessionHall sh join fetch sh.session s
            where sh.hall.id = :hallId and s.id <> :sessionId and s.sessionDate = :date
              and s.startTime < :end and s.endTime > :start
            """)
    List<SessionHall> findOverlappingUses(UUID hallId, UUID sessionId, LocalDate date, LocalTime start, LocalTime end);
}
