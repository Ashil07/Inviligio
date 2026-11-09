package com.invigilo.allocation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface UnfilledPositionRepository extends JpaRepository<UnfilledPosition, UUID> {

    @Query("select u from UnfilledPosition u join fetch u.sessionHall sh join fetch sh.hall join fetch sh.session where u.chartId = :chartId")
    List<UnfilledPosition> findForChart(UUID chartId);
}
