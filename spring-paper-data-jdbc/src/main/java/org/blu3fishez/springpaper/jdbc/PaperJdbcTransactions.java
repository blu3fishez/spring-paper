package org.blu3fishez.springpaper.jdbc;

import java.util.List;
import javax.sql.DataSource;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/** Creates a standard Spring JDBC manager with Paper's worker-only transaction-start constraint. */
public final class PaperJdbcTransactions {
    private PaperJdbcTransactions() {}
    public static JdbcTransactionManager workerOnly(DataSource source, PaperThreadAccess threads) {
        java.util.Objects.requireNonNull(threads);
        var manager = new JdbcTransactionManager(source);
        manager.setTransactionExecutionListeners(List.of(new TransactionExecutionListener() {
            @Override public void beforeBegin(TransactionExecution transaction) {
                // Spring calls this before doBegin acquires a connection.
                if (threads.isPrimaryThread()) throw new IllegalStateException("JDBC transactions must run on a worker; use PaperTasks.async");
            }
        }));
        return manager;
    }
}
