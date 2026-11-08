package com.invigilo.exam;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface ExamSessionRepository extends JpaRepository<ExamSession, UUID> {

    List<ExamSession> findByPeriodIdOrderBySessionDateAscStartTimeAsc(UUID periodId);

    boolean existsByPeriodIdAndSessionDateAndLabel(UUID periodId, LocalDate date, String label);

    boolean existsByPeriodIdAndSessionDateAndLabelAndIdNot(UUID periodId, LocalDate date, String label, UUID id);

    long countByPeriodId(UUID periodId);

    @Query("select count(s) from ExamSession s where s.period.id = :periodId and (s.sessionDate < :start or s.sessionDate > :end)")
    long countOutside(UUID periodId, LocalDate start, LocalDate end);
}
