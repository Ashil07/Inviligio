package com.invigilo.department;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered. */
interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findAllByOrderByCodeAsc();

    List<Department> findByActiveTrueOrderByCodeAsc();

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);
}
