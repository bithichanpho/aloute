package com.aloute.service;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessException;
import java.sql.SQLException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retries PostgreSQL work only after the previous transaction has completely rolled back.
 * Each attempt uses a fresh transaction; retrying statements inside an aborted transaction is unsafe.
 */
@Component
public class PostgresTransactionRetryExecutor {
    private static final int MAX_ATTEMPTS = 4;
    private static final long BACKOFF_MILLIS = 25L;

    private final TransactionTemplate transactionTemplate;

    public PostgresTransactionRetryExecutor(
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (RuntimeException failure) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(failure)) {
                    throw failure;
                }
                pauseBeforeRetry(attempt, failure);
            }
        }
    }

    private boolean isRetryable(Throwable failure) {
        boolean retryable = false;
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TransientDataAccessException) retryable = true;
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                // PostgreSQL serialization failure and deadlock victim errors are safe to retry
                // after the complete transaction has been rolled back.
                retryable |= "40001".equals(state) || "40P01".equals(state);
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return retryable;
    }

    private void pauseBeforeRetry(int attempt, RuntimeException originalFailure) {
        try {
            Thread.sleep(BACKOFF_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            originalFailure.addSuppressed(interrupted);
            throw originalFailure;
        }
    }
}
