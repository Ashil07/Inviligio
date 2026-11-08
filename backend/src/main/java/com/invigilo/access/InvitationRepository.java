package com.invigilo.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

/** Tenant-filtered; token lookups run in the system context (the invitee has no tenant yet). */
interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"faculty", "faculty.department"})
    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findByFacultyIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID facultyId);
}
