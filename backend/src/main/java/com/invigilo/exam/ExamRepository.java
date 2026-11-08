package com.invigilo.exam;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered. */
interface ExamRepository extends JpaRepository<Exam, UUID> {

    @EntityGraph(attributePaths = {"subject", "subject.department"})
    List<Exam> findBySessionId(UUID sessionId);

    @EntityGraph(attributePaths = {"subject"})
    List<Exam> findBySessionIdIn(Collection<UUID> sessionIds);

    boolean existsBySessionIdAndSubjectId(UUID sessionId, UUID subjectId);

    long countBySubjectId(UUID subjectId);
}
