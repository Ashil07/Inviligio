package com.invigilo.allocation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface DutyAssignmentRepository extends JpaRepository<DutyAssignment, UUID> {

    @Query("""
            select a from DutyAssignment a join fetch a.faculty f join fetch f.department
              join fetch a.sessionHall sh join fetch sh.hall join fetch sh.session
            where a.chartId = :chartId
            """)
    List<DutyAssignment> findForChart(UUID chartId);

    /** Duties per faculty in PUBLISHED charts of other periods (historical workload). */
    @Query("""
            select a.faculty.id, count(a) from DutyAssignment a, DutyChart c
            where a.chartId = c.id and c.status = com.invigilo.allocation.DutyChart.Status.PUBLISHED
              and c.periodId <> :periodId
              and a.status <> com.invigilo.allocation.DutyAssignment.Status.CANCELLED
            group by a.faculty.id
            """)
    List<Object[]> historicalDuties(UUID periodId);

    /** A faculty member's duties in published charts (what faculty may see), cancelled ones excluded. */
    @Query("""
            select a from DutyAssignment a join fetch a.sessionHall sh join fetch sh.hall join fetch sh.session s
              join fetch s.period
            where a.faculty.id = :facultyId
              and a.status <> com.invigilo.allocation.DutyAssignment.Status.CANCELLED
              and a.chartId in (select c.id from DutyChart c where c.status = com.invigilo.allocation.DutyChart.Status.PUBLISHED)
            """)
    List<DutyAssignment> findPublishedForFaculty(UUID facultyId);

    /** Everyone on duty in these session halls of these charts (to show who you work with). */
    @Query("""
            select a from DutyAssignment a join fetch a.faculty
            where a.chartId in :chartIds and a.sessionHall.id in :sessionHallIds
              and a.status <> com.invigilo.allocation.DutyAssignment.Status.CANCELLED
            """)
    List<DutyAssignment> findInHalls(java.util.Collection<UUID> chartIds, java.util.Collection<UUID> sessionHallIds);

    long countByFacultyId(UUID facultyId);

    long countByChartId(UUID chartId);
}
