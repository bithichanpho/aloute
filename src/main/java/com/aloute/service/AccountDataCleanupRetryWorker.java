package com.aloute.service;

import com.aloute.model.document.AccountCleanupJob;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Claims and retries durable account cleanup jobs after crashes or transient Mongo failures. */
@Service
public class AccountDataCleanupRetryWorker {
    private static final Logger log = LoggerFactory.getLogger(AccountDataCleanupRetryWorker.class);
    private static final int MAX_JOBS_PER_TICK = 25;
    private static final long CLAIM_LEASE_SECONDS = 300;
    private static final long MAX_BACKOFF_SECONDS = 6 * 60 * 60;

    private final MongoTemplate mongo;
    private final AccountDataCleanupService cleanupService;

    public AccountDataCleanupRetryWorker(MongoTemplate mongo, AccountDataCleanupService cleanupService) {
        this.mongo = mongo;
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            initialDelayString = "${app.account-cleanup.initial-delay-ms:30000}",
            fixedDelayString = "${app.account-cleanup.fixed-delay-ms:30000}")
    public void retryDueJobs() {
        for (int i = 0; i < MAX_JOBS_PER_TICK; i++) {
            final AccountCleanupJob job;
            try {
                job = claimOneDueJob();
            } catch (RuntimeException claimFailure) {
                // The retry record remains durable in Mongo. A later scheduled pass will retry
                // the claim when the database is reachable again.
                log.warn("Unable to claim account cleanup retry job (cause={})",
                        claimFailure.getClass().getSimpleName());
                return;
            }
            if (job == null) return;
            process(job);
        }
    }

    private AccountCleanupJob claimOneDueJob() {
        Instant now = Instant.now();
        Query due = Query.query(Criteria.where("nextAttemptAt").lte(now))
                .with(Sort.by(Sort.Direction.ASC, "nextAttemptAt"));
        Update claim = new Update().set("nextAttemptAt", now.plusSeconds(CLAIM_LEASE_SECONDS));
        return mongo.findAndModify(
                due,
                claim,
                FindAndModifyOptions.options().returnNew(true),
                AccountCleanupJob.class);
    }

    private void process(AccountCleanupJob job) {
        try {
            cleanupService.deleteUserData(
                    job.getUserId(), job.getPreviousAvatarUrl(), job.getPreviousCoverUrl());
            mongo.remove(Query.query(Criteria.where("_id").is(job.getId())), AccountCleanupJob.class);
            log.info("Account cleanup retry succeeded (userId={}, attempts={})",
                    job.getUserId(), job.getAttemptCount());
        } catch (RuntimeException failure) {
            int attempts = Math.min(30, Math.max(0, job.getAttemptCount()) + 1);
            long multiplier = 1L << Math.min(attempts, 10);
            long delay = Math.min(MAX_BACKOFF_SECONDS, 30L * multiplier);
            job.setAttemptCount(attempts);
            job.setLastErrorType(failure.getClass().getSimpleName());
            job.setNextAttemptAt(Instant.now().plusSeconds(delay));
            try {
                mongo.save(job);
                log.error("Account cleanup retry failed; next retry scheduled (userId={}, attempts={}, delaySeconds={}, cause={})",
                        job.getUserId(), attempts, delay, job.getLastErrorType());
            } catch (RuntimeException persistenceFailure) {
                // The original job remains leased for at most five minutes; if saving the updated
                // backoff fails, it becomes eligible again when the lease expires.
                log.error("Unable to update account cleanup retry state (userId={}, cause={})",
                        job.getUserId(), persistenceFailure.getClass().getSimpleName(), persistenceFailure);
            }
        }
    }
}
