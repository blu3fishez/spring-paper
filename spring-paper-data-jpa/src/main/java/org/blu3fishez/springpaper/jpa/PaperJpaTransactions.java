package org.blu3fishez.springpaper.jpa;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/** A standard Spring JPA manager with the same worker-only start contract as the JDBC integration. */
public final class PaperJpaTransactions {
    private PaperJpaTransactions() {}
    public static JpaTransactionManager workerOnly(EntityManagerFactory entities, PaperThreadAccess threads) {
        java.util.Objects.requireNonNull(threads);
        var manager = new JpaTransactionManager(entities);
        manager.setTransactionExecutionListeners(List.of(new TransactionExecutionListener() {
            @Override public void beforeBegin(TransactionExecution transaction) {
                if (threads.isPrimaryThread()) throw new IllegalStateException("JPA transactions must run on a worker; use PaperTasks.async");
            }
        }));
        return manager;
    }
}
