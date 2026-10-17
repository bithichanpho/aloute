package com.aloute.service;

import com.mongodb.MongoException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retries MongoDB work only after the previous transaction has completely rolled back.
 * Each attempt uses a fresh transaction; retrying statements inside an aborted transaction is unsafe.
 */
@Component
public class MongoTransactionRetryExecutor {
    private static final int MAX_ATTEMPTS = 4;
    private static final long BACKOFF_MILLIS = 25L;
    private static final int MONGO_WRITE_CONFLICT_CODE = 112;
    private static final String TRANSIENT_TRANSACTION_ERROR = "TransientTransactionError";

    private final TransactionTemplate transactionTemplate;

    public MongoTransactionRetryExecutor(
            @Qualifier("mongoTransactionManager") PlatformTransactionManager transactionManager) {
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
        boolean commitOutcomeUnknown = false;
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MongoException mongoException) {
                // Do not replay work when commit may already have succeeded. The driver has a
                // distinct retry protocol for UnknownTransactionCommitResult; blindly rerunning
                // the whole business operation could duplicate side effects.
                commitOutcomeUnknown |= mongoException.hasErrorLabel("UnknownTransactionCommitResult");
                retryable |= mongoException.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR)
                        || mongoException.getCode() == MONGO_WRITE_CONFLICT_CODE;
            }
            if (current instanceof TransientDataAccessException) {
                retryable = true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return retryable && !commitOutcomeUnknown;
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
