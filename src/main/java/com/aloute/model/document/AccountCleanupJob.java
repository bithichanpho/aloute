package com.aloute.model.document;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable retry record for cross-store account deletion cleanup. */
@Document(collection = "account_cleanup_jobs")
@lombok.Data
public class AccountCleanupJob {
    @Id
    private String id;

    private Long userId;
    private String previousAvatarUrl;
    private String previousCoverUrl;
    private int attemptCount;
    private String lastErrorType;

    private Instant createdAt = Instant.now();

    @Indexed
    private Instant nextAttemptAt = Instant.now();
}
