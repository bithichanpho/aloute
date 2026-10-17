package com.aloute.common.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects only after the active local transaction commits.
 *
 * <p>This does not create a distributed transaction across PostgreSQL and MongoDB. It is for
 * deferring WebSocket, push, notification, and external-storage side effects until the store
 * responsible for the primary write has committed.
 */
public final class TransactionCommitHooks {

    private TransactionCommitHooks() {}

    public static void afterCommit(Runnable action) {
        if (action == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }
}
