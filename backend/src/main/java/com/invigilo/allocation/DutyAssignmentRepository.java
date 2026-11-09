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

    long countByFacultyId(UUID facultyId);

    long countByChartId(UUID chartId);
}
