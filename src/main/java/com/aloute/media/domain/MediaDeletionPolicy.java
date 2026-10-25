package com.aloute.media.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Physical deletion needs explicit opt-in and dry-run must also be turned off. */
@Component
public class MediaDeletionPolicy {
    private final boolean enabled;
    private final boolean dryRun;

    public MediaDeletionPolicy(
            @Value("${app.media.cleanup.physical-deletion-enabled:false}") boolean enabled,
            @Value("${app.media.cleanup.dry-run:true}") boolean dryRun) {
        this.enabled = enabled;
        this.dryRun = dryRun;
    }

    public boolean permitsPhysicalDeletion() { return enabled && !dryRun; }
}
