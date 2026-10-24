package com.aloute.notifications.application;

import com.aloute.notifications.api.NotificationCleanup;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes existing rows and pending intents together in the owning PostgreSQL transaction. */
@Service
public class NotificationCleanupService implements NotificationCleanup {
    private static final List<String> COMMUNITY_TYPES = List.of(
            "COMMUNITY_JOIN_OK", "COMMUNITY_JOIN_NO", "COMMUNITY_ROLE");
    private final INotificationRepository notifications;
    private final INotificationOutboxRepository outbox;

    public NotificationCleanupService(INotificationRepository notifications, INotificationOutboxRepository outbox) {
        this.notifications = notifications;
        this.outbox = outbox;
    }

    @Transactional
    public void removePostReferences(Collection<String> postIds) {
        Objects.requireNonNull(postIds, "postIds");
        var ids = postIds.stream().filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            var batch = ids.subList(start, Math.min(start + 500, ids.size()));
            notifications.deleteByReferenceIdIn(batch);
            if (outbox != null) outbox.deleteByReferenceIdIn(batch);
        }
    }

    @Transactional
    public void removeCommunityReferences(Long communityId) {
        String id = Objects.requireNonNull(communityId, "communityId").toString();
        // Numeric community IDs must not accidentally remove another notification type.
        notifications.deleteByReferenceIdAndTypeIn(id, COMMUNITY_TYPES);
        if (outbox != null) outbox.deleteByReferenceIdAndNotificationTypeIn(id, COMMUNITY_TYPES);
    }
}
