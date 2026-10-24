package com.aloute.administration.api;

/** Authenticated report-moderation use case; HIDE/APPROVE semantics remain unchanged. */
public interface AccountModeration {
    void setHidden(Long actorId, Long targetId, boolean hidden);
}
