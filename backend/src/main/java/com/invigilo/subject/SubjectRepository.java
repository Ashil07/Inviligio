package com.invigilo.subject;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Tenant-filtered. */
interface SubjectRepository extends JpaRepository<Subject, UUID>, JpaSpecificationExecutor<Subject> {

    @Override
    @EntityGraph(attributePaths = "department")
    Page<Subject> findAll(Specification<Subject> spec, Pageable pageable);

    boolean existsByCode(String code);

    java.util.Optional<Subject> findByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    long countByDepartmentId(UUID departmentId);
}
