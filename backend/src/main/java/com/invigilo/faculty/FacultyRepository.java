package com.invigilo.faculty;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface FacultyRepository extends JpaRepository<Faculty, UUID>, JpaSpecificationExecutor<Faculty> {

    @Override
    @EntityGraph(attributePaths = "department")
    Page<Faculty> findAll(Specification<Faculty> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"department", "subjects", "subjects.subject"})
    Optional<Faculty> findWithSubjectsById(UUID id);

    Optional<Faculty> findByEmployeeCode(String employeeCode);

    boolean existsByEmployeeCode(String employeeCode);

    boolean existsByEmployeeCodeAndIdNot(String employeeCode, UUID id);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    long countByDepartmentId(UUID departmentId);

    @EntityGraph(attributePaths = "department")
    List<Faculty> findByActiveTrue();
}
