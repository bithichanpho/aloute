package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

import java.time.Instant;
import org.springframework.data.annotation.Id;

/** Durable retry record for account deletion cleanup. */
@lombok.Data
@PgDocument(collection = "account_cleanup_jobs")
public class AccountCleanupJob {
    @Id
    private String id;

    private Long userId;
    private String previousAvatarUrl;
    private String previousCoverUrl;
    private int attemptCount;
    private String lastErrorType;

    private Instant createdAt = Instant.now();

    private Instant nextAttemptAt = Instant.now();
}
