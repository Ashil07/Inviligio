package com.invigilo.college;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollegeRepository extends JpaRepository<College, UUID> {

    /** {@code code} must already be normalized with {@link College#normalizeCode}. */
    boolean existsByCode(String code);
}
