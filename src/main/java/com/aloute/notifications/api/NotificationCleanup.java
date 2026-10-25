package com.aloute.notifications.api;

import java.util.Collection;

/** Only for already-authorized deletion workflows; implementations own notification persistence. */
public interface NotificationCleanup {
    void removePostReferences(Collection<String> postIds);
    void removeCommunityReferences(Long communityId);
}
