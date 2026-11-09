package com.invigilo.availability;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface AvailabilityRepository extends JpaRepository<AvailabilityRequest, UUID> {

    String FETCH = "select r from AvailabilityRequest r join fetch r.faculty f join fetch f.department left join fetch r.session ";

    @Query(FETCH + "where f.id = :facultyId order by r.fromDate desc, r.createdAt desc")
    List<AvailabilityRequest> findForFaculty(UUID facultyId);

    /** PENDING or APPROVED requests of a faculty member whose dates intersect [from, to]. */
    @Query("""
            select r from AvailabilityRequest r left join fetch r.session
            where r.faculty.id = :facultyId and r.fromDate <= :to and r.toDate >= :from
              and r.status in (com.invigilo.availability.AvailabilityRequest.Status.PENDING,
                               com.invigilo.availability.AvailabilityRequest.Status.APPROVED)
            """)
    List<AvailabilityRequest> findActiveOverlapping(UUID facultyId, LocalDate from, LocalDate to);

    @Query(FETCH + "where (:status is null or r.status = :status) and (:departmentId is null or f.department.id = :departmentId) "
            + "order by r.fromDate desc, r.createdAt desc")
    List<AvailabilityRequest> search(AvailabilityRequest.Status status, UUID departmentId, Pageable page);

    /** PENDING or APPROVED requests touching a date range (allocation and the period overview). */
    @Query(FETCH + "where r.fromDate <= :to and r.toDate >= :from and r.status in "
            + "(com.invigilo.availability.AvailabilityRequest.Status.PENDING, com.invigilo.availability.AvailabilityRequest.Status.APPROVED)")
    List<AvailabilityRequest> findActiveBetween(LocalDate from, LocalDate to);
}
