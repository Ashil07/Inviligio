package com.invigilo.allocation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface DutyChartRepository extends JpaRepository<DutyChart, UUID> {

    Optional<DutyChart> findByPeriodIdAndStatus(UUID periodId, DutyChart.Status status);

    /**
     * The period's draft, locked until the transaction ends. Chart edits take this lock so two
     * admins cannot each make a change that is legal alone but breaks a rule together.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DutyChart c where c.periodId = :periodId and c.status = com.invigilo.allocation.DutyChart.Status.DRAFT")
    Optional<DutyChart> lockDraft(UUID periodId);

    List<DutyChart> findByPeriodIdOrderByChartVersionDesc(UUID periodId);

    @Query("select coalesce(max(c.chartVersion), 0) from DutyChart c where c.periodId = :periodId")
    int maxVersion(UUID periodId);
}
