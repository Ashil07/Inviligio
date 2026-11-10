package com.invigilo.allocation;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered. */
interface SwapRequestRepository extends JpaRepository<SwapRequest, UUID> {

    String FETCH = """
            select s from SwapRequest s
              join fetch s.requester r join fetch r.department
              join fetch s.target t join fetch t.department
              join fetch s.requesterAssignment ra join fetch ra.sessionHall rsh join fetch rsh.hall join fetch rsh.session rs
              join fetch rs.period
              left join fetch s.targetAssignment ta left join fetch ta.sessionHall tsh left join fetch tsh.hall
              left join fetch tsh.session
            """;

    @Query(FETCH + "where r.id = :facultyId or t.id = :facultyId order by s.createdAt desc")
    List<SwapRequest> findInvolving(UUID facultyId);

    /** {@code departmentId} null: every department; {@code statuses} empty is not allowed, pass all. */
    @Query(FETCH + "where s.status in :statuses and (:departmentId is null or r.department.id = :departmentId) order by s.createdAt desc")
    List<SwapRequest> search(Collection<SwapRequest.Status> statuses, UUID departmentId);

    boolean existsByRequesterAssignment_IdAndStatusIn(UUID assignmentId, Collection<SwapRequest.Status> statuses);

    long countByRequester_IdOrTarget_Id(UUID requesterId, UUID targetId);

    long countByChartIdAndStatusAndAppliedAtAfter(UUID chartId, SwapRequest.Status status, java.time.Instant after);
}
