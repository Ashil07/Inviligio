package com.invigilo.tenant;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a new transaction in the system (unfiltered) context.
 *
 * <p>The ordering matters: the system flag is set first, then the transaction (and Hibernate
 * session) opens, so Hibernate sees the root tenant. Joining an existing tenant transaction would
 * silently keep that tenant's session, so nesting is rejected.
 */
@Component
public class SystemTransactions {

    private final TransactionTemplate tx;

    public SystemTransactions(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    public <T> T execute(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("System transactions must not run inside another transaction");
        }
        return TenantContext.callAsSystem(() -> tx.execute(status -> work.get()));
    }

    public void run(Runnable work) {
        execute(() -> {
            work.run();
            return null;
        });
    }
}
