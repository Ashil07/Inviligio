package com.invigilo.allocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface DutyChartRepository extends JpaRepository<DutyChart, UUID> {

    Optional<DutyChart> findByPeriodIdAndStatus(UUID periodId, DutyChart.Status status);

    List<DutyChart> findByPeriodIdOrderByChartVersionDesc(UUID periodId);

    @Query("select coalesce(max(c.chartVersion), 0) from DutyChart c where c.periodId = :periodId")
    int maxVersion(UUID periodId);
}
