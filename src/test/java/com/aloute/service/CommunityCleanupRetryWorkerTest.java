package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.entity.CommunityCleanupJob;
import com.aloute.repository.ICommunityCleanupJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class CommunityCleanupRetryWorkerTest {
    @Test
    void deletesJobAfterSuccessfulCleanup() {
        ICommunityCleanupJobRepository repository = mock(ICommunityCleanupJobRepository.class);
        CommunityDataCleanupService cleanup = mock(CommunityDataCleanupService.class);
        CommunityCleanupJob job = job(7L);
        when(repository.findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(job));

        new CommunityCleanupRetryWorker(repository, cleanup).retryDueJobs();

        verify(cleanup).deleteCommunityData(7L, "avatar", "cover");
        verify(repository).deleteById(7L);
        verify(repository, never()).save(any());
    }

    @Test
    void keepsJobAndSchedulesBackoffAfterFailure() {
        ICommunityCleanupJobRepository repository = mock(ICommunityCleanupJobRepository.class);
        CommunityDataCleanupService cleanup = mock(CommunityDataCleanupService.class);
        CommunityCleanupJob job = job(8L);
        when(repository.findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(job));
        doThrow(new IllegalStateException("mongo unavailable"))
                .when(cleanup).deleteCommunityData(eq(8L), any(), any());
        LocalDateTime before = LocalDateTime.now();

        new CommunityCleanupRetryWorker(repository, cleanup).retryDueJobs();

        ArgumentCaptor<CommunityCleanupJob> saved = ArgumentCaptor.forClass(CommunityCleanupJob.class);
        verify(repository).save(saved.capture());
        assertEquals(1, saved.getValue().getAttemptCount());
        assertNotNull(saved.getValue().getLastError());
        assertTrue(saved.getValue().getLastError().contains("mongo unavailable"));
        assertTrue(saved.getValue().getNextAttemptAt().isAfter(before));
        verify(repository, never()).deleteById(8L);
    }

    private CommunityCleanupJob job(Long id) {
        CommunityCleanupJob job = new CommunityCleanupJob();
        job.setCommunityId(id);
        job.setAvatarUrl("avatar");
        job.setCoverUrl("cover");
        job.setNextAttemptAt(LocalDateTime.now().minusMinutes(1));
        return job;
    }
}
