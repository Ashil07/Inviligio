package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult.Role;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered. */
interface RemunerationRateRepository extends JpaRepository<RemunerationRate, UUID> {

    List<RemunerationRate> findAllByOrderByRoleAscEffectiveFromDesc();

    boolean existsByRoleAndEffectiveFrom(Role role, LocalDate effectiveFrom);
}
