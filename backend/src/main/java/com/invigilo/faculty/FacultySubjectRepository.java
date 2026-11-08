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
interface FacultySubjectRepository extends JpaRepository<FacultySubject, UUID> {

    long countBySubjectId(UUID subjectId);

    @Query("select fs.faculty.id, count(fs) from FacultySubject fs where fs.faculty.id in :facultyIds group by fs.faculty.id")
    List<Object[]> countByFaculty(Collection<UUID> facultyIds);
}
