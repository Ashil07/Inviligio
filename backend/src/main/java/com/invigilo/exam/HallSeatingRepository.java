package com.invigilo.exam;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface HallSeatingRepository extends JpaRepository<HallSeating, UUID> {

    @Query("select coalesce(sum(s.studentCount), 0) from HallSeating s where s.exam.id = :examId")
    long seatedStudents(UUID examId);

    /** Seated students of each exam in session halls other than {@code excludeSessionHallId}. */
    @Query("""
            select s.exam.id, sum(s.studentCount) from HallSeating s
            where s.exam.id in :examIds and s.sessionHall.id <> :excludeSessionHallId
            group by s.exam.id
            """)
    List<Object[]> seatedElsewhere(Collection<UUID> examIds, UUID excludeSessionHallId);
}
