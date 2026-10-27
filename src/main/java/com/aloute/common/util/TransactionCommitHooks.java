package com.aloute.common.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects only after the active local transaction commits.
 *
 * <p>This does not create a distributed transaction across PostgreSQL and PostgreSQL. It is for
 * deferring WebSocket, push, notification, and external-storage side effects until the store
 * responsible for the primary write has committed.
 */
public final class TransactionCommitHooks {
    private static final ThreadLocal<Boolean> AFTER_COMMIT_CALLBACK = ThreadLocal.withInitial(() -> false);

    private TransactionCommitHooks() {}

    /** True only while running a callback after the original transaction has already committed. */
    public static boolean isExecutingAfterCommitCallback() {
        return Boolean.TRUE.equals(AFTER_COMMIT_CALLBACK.get());
    }

    private static void runAfterCommitCallback(Runnable action) {
        boolean previous = isExecutingAfterCommitCallback();
        AFTER_COMMIT_CALLBACK.set(true);
        try {
            action.run();
        } finally {
            if (previous) AFTER_COMMIT_CALLBACK.set(true);
            else AFTER_COMMIT_CALLBACK.remove();
        }
    }

    public static void afterCommit(Runnable action) {
        if (action == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            runAfterCommitCallback(action);
                        }
                    });
        } else {
            action.run();
        }
    }
}
