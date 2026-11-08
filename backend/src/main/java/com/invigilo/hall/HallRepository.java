package com.invigilo.hall;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered. */
interface HallRepository extends JpaRepository<Hall, UUID> {

    List<Hall> findAllByOrderByCodeAsc();

    List<Hall> findByActiveTrueOrderByCodeAsc();

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);
}
