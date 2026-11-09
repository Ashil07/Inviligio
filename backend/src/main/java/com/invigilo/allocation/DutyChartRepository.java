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

    /**
     * Like {@link #lockDraft} but also increments the chart's version, so an edit to any of its
     * duties makes a reviewer's "publish this version" request stale.
     */
    @Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    @Query("select c from DutyChart c where c.periodId = :periodId and c.status = com.invigilo.allocation.DutyChart.Status.DRAFT")
    Optional<DutyChart> lockDraftForEdit(UUID periodId);

    Optional<DutyChart> findByPeriodIdAndChartVersion(UUID periodId, int chartVersion);

    List<DutyChart> findByPeriodIdOrderByChartVersionDesc(UUID periodId);

    List<DutyChart> findByStatus(DutyChart.Status status);

    @Query("select coalesce(max(c.chartVersion), 0) from DutyChart c where c.periodId = :periodId")
    int maxVersion(UUID periodId);
}
