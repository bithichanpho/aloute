package com.aloute.social.api;

import java.util.Collection;
import java.util.Set;

/** Preserves the existing active-account and bidirectional block policy. */
public interface ProfileVisibility {
    boolean profileVisible(Long viewerId, Long authorId);
    Set<Long> visibleProfileIds(Long viewerId, Collection<Long> authorIds);
}
