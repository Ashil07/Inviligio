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
interface FacultyRestrictionRepository extends JpaRepository<FacultyRestriction, UUID> {

    @EntityGraph(attributePaths = {"hall", "department"})
    List<FacultyRestriction> findByFacultyIdOrderByCreatedAtAsc(UUID facultyId);

    long countByHallId(UUID hallId);

    long countByDepartmentId(UUID departmentId);
}
