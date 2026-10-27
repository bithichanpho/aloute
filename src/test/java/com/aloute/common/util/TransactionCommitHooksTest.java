package com.aloute.common.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionCommitHooksTest {
    @AfterEach
    void clearSynchronizationState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void exposesAfterCommitContextOnlyWhileCallbackRuns() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        AtomicBoolean observedInsideCallback = new AtomicBoolean(false);

        TransactionCommitHooks.afterCommit(() ->
                observedInsideCallback.set(TransactionCommitHooks.isExecutingAfterCommitCallback()));

        assertFalse(TransactionCommitHooks.isExecutingAfterCommitCallback());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        assertTrue(observedInsideCallback.get());
        assertFalse(TransactionCommitHooks.isExecutingAfterCommitCallback());
    }
}
