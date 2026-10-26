package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.document.AccountCleanupJob;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.aloute.persistence.postgres.document.FindAndModifyOptions;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;

@ExtendWith(MockitoExtension.class)
class AccountDataCleanupRetryWorkerTest {
    @Mock private PostgresDocumentStore documentStore;
    @Mock private AccountDataCleanupService cleanupService;

    private AccountDataCleanupRetryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AccountDataCleanupRetryWorker(documentStore, cleanupService);
    }

    @Test
    void removesRetryJobOnlyAfterCleanupSucceeds() {
        AccountCleanupJob job = job();
        when(documentStore.findAndModify(
                        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                        eq(AccountCleanupJob.class)))
                .thenReturn(job)
                .thenReturn(null);

        worker.retryDueJobs();

        verify(cleanupService).deleteUserData(42L, "avatar-url", "cover-url");
        verify(documentStore).remove(any(Query.class), eq(AccountCleanupJob.class));
    }

    @Test
    void persistsBackoffWhenCleanupFails() {
        AccountCleanupJob job = job();
        when(documentStore.findAndModify(
                        any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                        eq(AccountCleanupJob.class)))
                .thenReturn(job)
                .thenReturn(null);
        doThrow(new IllegalStateException("simulated cleanup failure"))
                .when(cleanupService).deleteUserData(42L, "avatar-url", "cover-url");
        when(documentStore.save(job)).thenReturn(job);
        Instant before = Instant.now();

        worker.retryDueJobs();

        assertEquals(1, job.getAttemptCount());
        assertEquals("IllegalStateException", job.getLastErrorType());
        assertTrue(job.getNextAttemptAt().isAfter(before));
        verify(documentStore).save(job);
        verify(documentStore, never()).remove(any(Query.class), eq(AccountCleanupJob.class));
    }

    private AccountCleanupJob job() {
        AccountCleanupJob job = new AccountCleanupJob();
        job.setId("42");
        job.setUserId(42L);
        job.setPreviousAvatarUrl("avatar-url");
        job.setPreviousCoverUrl("cover-url");
        job.setNextAttemptAt(Instant.now().minusSeconds(1));
        return job;
    }
}
