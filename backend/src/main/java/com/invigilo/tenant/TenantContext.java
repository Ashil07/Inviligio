package com.invigilo.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The college whose data the current thread may touch.
 *
 * <p>Set from the authenticated JWT by {@link TenantFilter} for every API request, and read by
 * Hibernate through {@link TenantIdentifierResolver}. Hibernate reads it when a session opens
 * (i.e. when a transaction starts), so the context must be in place <em>before</em> the
 * transaction begins — use {@link SystemTransactions} for system-context work.
 *
 * <p>The <em>system</em> context disables tenant filtering. It exists only for flows that
 * legitimately run before a tenant is known (login, registration, token refresh).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> COLLEGE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<UUID> currentCollegeId() {
        return Optional.ofNullable(COLLEGE.get());
    }

    public static UUID requireCollegeId() {
        UUID id = COLLEGE.get();
        if (id == null) {
            throw new IllegalStateException("No tenant in context");
        }
        return id;
    }

    public static boolean isSystem() {
        return Boolean.TRUE.equals(SYSTEM.get());
    }

    /** Runs {@code work} as the given college. Restores the previous context afterwards. */
    public static <T> T callAsTenant(UUID collegeId, Supplier<T> work) {
        UUID previousCollege = COLLEGE.get();
        Boolean previousSystem = SYSTEM.get();
        COLLEGE.set(collegeId);
        SYSTEM.remove();
        try {
            return work.get();
        } finally {
            restore(previousCollege, previousSystem);
        }
    }

    /** Runs {@code work} without tenant filtering. Prefer {@link SystemTransactions}. */
    public static <T> T callAsSystem(Supplier<T> work) {
        UUID previousCollege = COLLEGE.get();
        Boolean previousSystem = SYSTEM.get();
        COLLEGE.remove();
        SYSTEM.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            restore(previousCollege, previousSystem);
        }
    }

    static void set(UUID collegeId) {
        COLLEGE.set(collegeId);
        SYSTEM.remove();
    }

    static void clear() {
        COLLEGE.remove();
        SYSTEM.remove();
    }

    private static void restore(UUID college, Boolean system) {
        if (college == null) COLLEGE.remove(); else COLLEGE.set(college);
        if (system == null) SYSTEM.remove(); else SYSTEM.set(system);
    }
}
