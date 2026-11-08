package com.invigilo.exam;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered. */
interface ExamPeriodRepository extends JpaRepository<ExamPeriod, UUID> {

    List<ExamPeriod> findAllByOrderByStartDateDesc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);
}
