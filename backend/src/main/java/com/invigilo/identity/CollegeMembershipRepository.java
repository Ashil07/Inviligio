package com.invigilo.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered by Hibernate. Cross-college lookups only work in the system context. */
public interface CollegeMembershipRepository extends JpaRepository<CollegeMembership, UUID> {

    /** All active memberships of a user, with college and user loaded. Used at login (system context). */
    @Query("""
            select m from CollegeMembership m
              join fetch m.college c
              join fetch m.user u
            where u.id = :userId and m.status = com.invigilo.identity.CollegeMembership.Status.ACTIVE
            order by c.name
            """)
    List<CollegeMembership> findActiveByUserId(UUID userId);

    @Query("select m from CollegeMembership m join fetch m.college join fetch m.user where m.id = :id")
    Optional<CollegeMembership> findWithDetailsById(UUID id);

    @Query("select m from CollegeMembership m join fetch m.user where m.facultyId = :facultyId")
    Optional<CollegeMembership> findByFacultyId(UUID facultyId);

    /** In the current tenant (or explicitly filtered by college in the system context). */
    @Query("select m from CollegeMembership m where m.user.id = :userId and m.collegeId = :collegeId")
    Optional<CollegeMembership> findByUserIdAndCollegeId(UUID userId, UUID collegeId);

    long countByFacultyId(UUID facultyId);

    @Query("""
            select count(distinct m) from CollegeMembership m join m.roles r
            where r = com.invigilo.identity.Role.EXAM_CELL_ADMIN
              and m.status = com.invigilo.identity.CollegeMembership.Status.ACTIVE
            """)
    long countActiveAdmins();
}
