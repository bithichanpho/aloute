package com.aloute.service;

import com.aloute.model.entity.CommunityCleanupJob;
import com.aloute.repository.ICommunityCleanupJobRepository;
import java.time.LocalDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Processes durable PostgreSQL cleanup intents and retries transient Mongo/media failures. */
@Service
public class CommunityCleanupRetryWorker {
    private static final Logger log = LoggerFactory.getLogger(CommunityCleanupRetryWorker.class);
    private static final int BATCH_SIZE = 10;
    private static final long MAX_BACKOFF_SECONDS = 6 * 60 * 60;

    private final ICommunityCleanupJobRepository repository;
    private final CommunityDataCleanupService cleanupService;

    public CommunityCleanupRetryWorker(
            ICommunityCleanupJobRepository repository,
            CommunityDataCleanupService cleanupService) {
        this.repository = repository;
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            initialDelayString = "${app.community-cleanup.initial-delay-ms:5000}",
            fixedDelayString = "${app.community-cleanup.fixed-delay-ms:30000}")
    @SchedulerLock(name = "communityCleanupWorker", lockAtMostFor = "PT10M")
    public void retryDueJobs() {
        var jobs = repository.findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));
        for (CommunityCleanupJob job : jobs) process(job);
    }

    private void process(CommunityCleanupJob job) {
        try {
            cleanupService.deleteCommunityData(
                    job.getCommunityId(), job.getAvatarUrl(), job.getCoverUrl());
            repository.deleteById(job.getCommunityId());
            log.info("Community data cleanup succeeded (communityId={}, attempts={})",
                    job.getCommunityId(), job.getAttemptCount());
        } catch (RuntimeException failure) {
            int attempts = Math.min(30, Math.max(0, job.getAttemptCount()) + 1);
            long delay = Math.min(MAX_BACKOFF_SECONDS, 30L << Math.min(attempts, 10));
            job.setAttemptCount(attempts);
            job.setLastError(summarize(failure));
            job.setNextAttemptAt(LocalDateTime.now().plusSeconds(delay));
            repository.save(job);
            log.warn(
                    "Community data cleanup failed; retry scheduled (communityId={}, attempt={}, delaySeconds={}, cause={})",
                    job.getCommunityId(), attempts, delay, failure.getClass().getSimpleName());
        }
    }

    private String summarize(RuntimeException failure) {
        String message = failure.getMessage();
        String summary = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return summary.length() <= 1000 ? summary : summary.substring(0, 1000);
    }
}
