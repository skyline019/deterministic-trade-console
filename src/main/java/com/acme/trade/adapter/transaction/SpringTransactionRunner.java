package com.acme.trade.adapter.transaction;

import com.acme.trade.application.port.TransactionMode;
import com.acme.trade.application.port.TransactionRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** The only place where Spring's transaction API is coupled to the use-case port. */
public final class SpringTransactionRunner implements TransactionRunner {
    private final TransactionTemplate write;
    private final TransactionTemplate readOnly;

    public SpringTransactionRunner(PlatformTransactionManager transactionManager, int timeoutSeconds) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("transaction timeout must be at least one second");
        }
        write = template(transactionManager, "trade-write", false, timeoutSeconds);
        readOnly = template(transactionManager, "trade-read-only", true, timeoutSeconds);
    }

    private static TransactionTemplate template(
            PlatformTransactionManager manager, String name, boolean readOnly, int timeoutSeconds) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setName(name);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(readOnly);
        template.setTimeout(timeoutSeconds);
        return template;
    }

    @Override
    public <T> T inTransaction(TransactionMode mode, Supplier<T> action) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(action, "action");
        TransactionTemplate template = mode == TransactionMode.WRITE ? write : readOnly;
        return template.execute(status -> action.get());
    }
}
