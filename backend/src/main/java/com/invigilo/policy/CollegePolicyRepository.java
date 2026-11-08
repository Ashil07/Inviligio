package com.invigilo.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-filtered: every method only sees the current college's versions. */
interface CollegePolicyRepository extends JpaRepository<CollegePolicy, UUID> {

    @EntityGraph(attributePaths = "createdBy")
    Optional<CollegePolicy> findFirstByOrderByPolicyVersionDesc();

    @EntityGraph(attributePaths = "createdBy")
    Optional<CollegePolicy> findByPolicyVersion(int policyVersion);

    @EntityGraph(attributePaths = "createdBy")
    List<CollegePolicy> findAllByOrderByPolicyVersionDesc();
}
