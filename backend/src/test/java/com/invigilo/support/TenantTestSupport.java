package com.invigilo.support;

import com.invigilo.tenant.TenantContext;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs repository code in a transaction as a given college, as a request would. Use it to prove
 * that tenant-owned data of one college is invisible from another (see TenantIsolationIntegrationTest).
 */
public class TenantTestSupport {

    private final TransactionTemplate tx;

    TenantTestSupport(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    public <T> T asTenant(UUID collegeId, Supplier<T> work) {
        return TenantContext.callAsTenant(collegeId, () -> tx.execute(status -> work.get()));
    }

    public <T> T asSystem(Supplier<T> work) {
        return TenantContext.callAsSystem(() -> tx.execute(status -> work.get()));
    }

    /** No tenant and not system: what an unauthenticated code path would see. */
    public <T> T withoutTenant(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }
}
