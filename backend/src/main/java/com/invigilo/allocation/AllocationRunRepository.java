package com.invigilo.allocation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered. */
interface AllocationRunRepository extends JpaRepository<AllocationRun, UUID> {
}
