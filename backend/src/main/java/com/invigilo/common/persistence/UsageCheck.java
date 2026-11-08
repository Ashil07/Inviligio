package com.invigilo.common.persistence;

import java.util.UUID;

/**
 * Lets features that reference a record veto its deletion without the owning feature depending
 * on them. Example: the subject feature registers a {@code UsageCheck<Department>} that counts
 * subjects per department; the faculty feature (M4) adds another. The owner collects all beans of
 * {@code UsageCheck<Owner>} and refuses deletion while any count is non-zero.
 */
public interface UsageCheck<T> {

    /** Human-readable plural noun, e.g. "subjects". */
    String usedBy();

    /** Number of rows (in the current college) that reference {@code id}. */
    long countUsages(UUID id);
}
