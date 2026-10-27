package com.aloute.notifications.application;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.response.NotificationCursorPage;
import com.aloute.dto.response.NotificationResponse;
import com.aloute.model.entity.Notification;
import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.repository.INotificationRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.aloute.notifications.api.*;
import com.aloute.posts.api.PostNotificationLookup;
import com.aloute.social.api.ProfileVisibility;
import com.aloute.users.api.UserDirectory;

public class NotificationApplicationService implements Notifications {
    private static final java.util.Set<String> REMOVED_FEATURE_NOTIFICATION_TYPES =
            java.util.Set.of("CLOSE_FRIEND_REQUEST", "CLOSE_FRIEND_ACCEPTED", "FOLLOW_REQUEST");
    private static final java.util.Set<String> SYSTEM_NOTIFICATION_TYPES = java.util.Set.of(
            "POST_MODERATION_FLAGGED", "POST_MODERATION_HIDDEN", "FOLLOW_REQUEST_ACCEPTED");
    private static final Logger log = LoggerFactory.getLogger(NotificationApplicationService.class);

    private final ProfileVisibility access;
    private final INotificationRepository notificationRepository;
    private final UserDirectory userDirectory;
    private final PostNotificationLookup posts;
    private final SimpMessagingTemplate messagingTemplate;
    private final ReliableNotificationPush pushNotificationService;
    private final NotificationIntents outboxService;

    public NotificationApplicationService(
            INotificationRepository notificationRepository,
            UserDirectory userDirectory,
            PostNotificationLookup posts,
            SimpMessagingTemplate messagingTemplate,
            ProfileVisibility access,
            ReliableNotificationPush pushNotificationService,
            NotificationIntents outboxService) {
        this.access = access;
        this.notificationRepository = notificationRepository;
        this.userDirectory = userDirectory;
        this.posts = posts;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
        this.outboxService = outboxService;
    }

    public void notifyPostLiked(String postId, Long likerId) {
        posts.ownerOf(postId).ifPresent(owner -> notify(owner, likerId, "LIKE", postId,
                "đã thích bài viết của bạn"));
    }

    /**
     * Queues an acceptance notice for the original requester. The follow decision has already
     * been authorized by SocialFeatureService, so this path must not silently drop the event
     * because profile visibility checks are asymmetric for private accounts.
     */
    @Override
    public void notifyFollowRequestAccepted(Long requesterId, Long acceptedByUserId) {
        if (requesterId == null || acceptedByUserId == null || requesterId.equals(acceptedByUserId)) return;
        String acceptedBy = userDirectory.username(acceptedByUserId).orElse("Người dùng");
        try {
            enqueueDurably(requesterId, acceptedByUserId, "FOLLOW_REQUEST_ACCEPTED",
                    String.valueOf(acceptedByUserId), acceptedBy + " đã chấp nhận yêu cầu theo dõi của bạn");
        } catch (RuntimeException failure) {
            log.warn("Could not queue follow-request acceptance notice (requesterId={}, acceptedById={}, cause={})",
                    requesterId, acceptedByUserId, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /**
     * Queues a system-generated moderation notice. The author's own id is used as the outbox
     * sender to preserve the existing non-null schema; the system type bypasses actor visibility
     * checks and is rendered as the exact message rather than "username + message".
     */
    @Override
    public void notifyPostModerationResult(
            Long authorId, String postId, com.aloute.model.document.ModerationStatus status) {
        if (authorId == null || postId == null || postId.isBlank() || status == null) return;
        final String type;
        final String content;
        if (status == com.aloute.model.document.ModerationStatus.FLAGGED) {
            type = "POST_MODERATION_FLAGGED";
            content = "Bài viết của bạn có chứa nội dung bị đánh dấu. Bài viết đang bị hạn chế hiển thị và cần được xem xét.";
        } else if (status == com.aloute.model.document.ModerationStatus.HIDDEN) {
            type = "POST_MODERATION_HIDDEN";
            content = "Bài viết của bạn đã bị ẩn sau khi kiểm duyệt vì nội dung không được chấp nhận.";
        } else {
            return;
        }
        try {
            enqueueDurably(authorId, authorId, type, postId, content);
        } catch (RuntimeException failure) {
            log.warn("Could not queue post moderation notice (postId={}, status={}, cause={})",
                    postId, status, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /**
     * Persists a delivery intent instead of calling WebSocket/FCM inline. When invoked from a JPA
     * transaction this write participates in that transaction; otherwise repository.save starts a
     * short transaction of its own. The worker provides retry and idempotency.
     */
    public void notify(
            Long receiverId, Long senderId, String type, String referenceId, String content) {
        // Follow requests now live in their own navigation page, not in the activity timeline.
        if ("FOLLOW_REQUEST".equals(type)) return;
        if (receiverId == null || senderId == null || receiverId.equals(senderId)
                || !access.profileVisible(receiverId, senderId)
                || !access.profileVisible(senderId, receiverId)) return;
        try {
            enqueueDurably(receiverId, senderId, type, referenceId, content);
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not persist notification outbox event (receiverId={}, type={}, cause={})",
                    receiverId, type, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Called by the durable worker; outboxEventId makes the SQL insert retry-safe. */
    /** One scalar projection for all actors/recipients in a bounded worker batch. */
    public java.util.Map<Long, String> deliveryDirectory(List<NotificationOutboxEvent> events) {
        return userDirectory.usernames(events.stream()
                .flatMap(event -> java.util.stream.Stream.of(event.getSenderId(), event.getReceiverId()))
                .filter(java.util.Objects::nonNull)
                .distinct().toList());
    }

    /** Compatibility path for single-event callers; authorization precedes all directory access. */
    public void deliverQueued(NotificationOutboxEvent event) {
        deliverQueued(event, () -> {
            java.util.Map<Long, String> names = new java.util.HashMap<>();
            userDirectory.username(event.getSenderId()).ifPresent(name -> names.put(event.getSenderId(), name));
            userDirectory.username(event.getReceiverId()).ifPresent(name -> names.put(event.getReceiverId(), name));
            return names;
        });
    }

    public void deliverQueued(NotificationOutboxEvent event, java.util.Map<Long, String> directory) {
        deliverQueued(event, () -> directory);
    }

    private void deliverQueued(NotificationOutboxEvent event,
            java.util.function.Supplier<java.util.Map<Long, String>> directory) {
        // Old FOLLOW_REQUEST outbox records must not reappear in Activity after this feature moved
        // to the dedicated Follow Requests page. The outbox worker deletes this event after return.
        if ("FOLLOW_REQUEST".equals(event.getNotificationType())) return;
        boolean systemNotice = SYSTEM_NOTIFICATION_TYPES.contains(event.getNotificationType());
        if (!systemNotice && (event.getReceiverId().equals(event.getSenderId())
                || !access.profileVisible(event.getReceiverId(), event.getSenderId())
                || !access.profileVisible(event.getSenderId(), event.getReceiverId()))) return;

        // A queued mention/reaction must not reveal a post after access was revoked.
        // Community review uses the community owner's current manager policy instead.
        if (java.util.Set.of("LIKE", "COMMENT", "REPOST", "MENTION", "COMMUNITY_POST_OK", "COMMUNITY_POST_NO")
                .contains(event.getNotificationType())
                && !posts.isReadable(event.getReferenceId(), event.getReceiverId())) return;
        if ("COMMUNITY_REVIEW".equals(event.getNotificationType())
                && !posts.canReviewCommunityPost(event.getReferenceId(), event.getReceiverId())) return;

        var names = directory.get();
        String actorName = systemNotice ? "" : names.getOrDefault(event.getSenderId(), "Ai đó");
        Notification saved = notificationRepository.findByOutboxEventId(event.getId())
                .orElseGet(() -> {
                    Notification notification = new Notification();
                    notification.setOutboxEventId(event.getId());
                    notification.setReceiverId(event.getReceiverId());
                    notification.setSenderId(event.getSenderId());
                    notification.setType(event.getNotificationType());
                    notification.setReferenceId(event.getReferenceId());
                    notification.setContent(systemNotice ? event.getContent() : actorName + " " + event.getContent());
                    return notificationRepository.save(notification);
                });

        // The SQL notification row is the durable source of truth. Deliver the in-app event first,
        // independently of Firebase, because an FCM outage must not suppress an online user's
        // notification. The outbox checkpoint prevents duplicate WebSocket toasts while FCM retries.
        if (event.getInAppDeliveredAt() == null) {
            String recipientUsername = names.get(event.getReceiverId());
            if (recipientUsername != null && !recipientUsername.isBlank()) {
                messagingTemplate.convertAndSendToUser(
                        recipientUsername, "/queue/notifications", toResponse(saved));
                java.time.LocalDateTime deliveredAt = java.time.LocalDateTime.now();
                event.setInAppDeliveredAt(deliveredAt);
                outboxService.markInAppDelivered(event.getId(), deliveredAt);
            }
        }

        // Keep the event in the durable outbox for transient FCM failures, but never use FCM as
        // a prerequisite for persisting or showing the in-app notification.
        pushNotificationService.sendNotificationToUserReliably(
                event.getReceiverId(), "ALOUTE", saved.getContent(), "/activity");
    }

    private void enqueueDurably(Long receiverId, Long senderId, String type,
            String referenceId, String content) {
        if (TransactionCommitHooks.isExecutingAfterCommitCallback()) {
            outboxService.enqueueAfterCommit(receiverId, senderId, type, referenceId, content);
        } else {
            outboxService.enqueue(receiverId, senderId, type, referenceId, content);
        }
    }

    public List<NotificationResponse> getMyNotifications(Long userId) {
        var notifications = notificationRepository.findByReceiverIdOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 50));
        var visible = access.visibleProfileIds(
                userId, notifications.stream().map(Notification::getSenderId)
                        .filter(java.util.Objects::nonNull).toList());
        return notifications.stream()
                .filter(n -> !REMOVED_FEATURE_NOTIFICATION_TYPES.contains(n.getType()))
                .filter(n -> SYSTEM_NOTIFICATION_TYPES.contains(n.getType()) || visible.contains(n.getSenderId()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public NotificationCursorPage getMyNotifications(Long userId, Long beforeId, int requestedSize) {
        int size = Math.max(1, Math.min(50, requestedSize));
        var pageable = org.springframework.data.domain.PageRequest.of(0, size + 1);
        var notifications = beforeId == null
                ? notificationRepository.findByReceiverIdOrderByCreatedAtDesc(userId, pageable)
                : notificationRepository.findByReceiverIdAndIdLessThanOrderByIdDesc(
                        userId, beforeId, pageable);
        boolean hasMore = notifications.size() > size;
        if (hasMore) notifications = new java.util.ArrayList<>(notifications.subList(0, size));
        var visible = access.visibleProfileIds(
                userId, notifications.stream().map(Notification::getSenderId)
                        .filter(java.util.Objects::nonNull).toList());
        List<NotificationResponse> items = notifications.stream()
                .filter(n -> !REMOVED_FEATURE_NOTIFICATION_TYPES.contains(n.getType()))
                .filter(n -> SYSTEM_NOTIFICATION_TYPES.contains(n.getType()) || visible.contains(n.getSenderId()))
                .map(this::toResponse)
                .toList();
        Long nextCursor = hasMore && !notifications.isEmpty()
                ? notifications.get(notifications.size() - 1).getId()
                : null;
        return new NotificationCursorPage(items, nextCursor, hasMore);
    }

    public long countUnread(Long userId) {
        long total = notificationRepository.countByReceiverIdAndReadFalse(userId);
        long removedFeatureUnread = notificationRepository.countByReceiverIdAndReadFalseAndTypeIn(
                userId, List.copyOf(REMOVED_FEATURE_NOTIFICATION_TYPES));
        return Math.max(0L, total - removedFeatureUnread);
    }

    public void markAllAsRead(Long userId) {
        notificationRepository.markAllRead(userId);
    }

    public void markAsRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .filter(item -> userId.equals(item.getReceiverId()))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông báo"));
        notification.setRead(true);
        notificationRepository.save(notification);
    }

    public void delete(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .filter(item -> userId.equals(item.getReceiverId()))
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông báo"));
        notificationRepository.delete(notification);
    }

    private NotificationResponse toResponse(Notification n) {
        NotificationResponse dto = new NotificationResponse();
        dto.setId(n.getId());
        dto.setType(n.getType());
        dto.setContent(n.getContent());
        dto.setReferenceId(n.getReferenceId());
        dto.setRead(n.isRead());
        dto.setCreatedAt(n.getCreatedAt());
        return dto;
    }
}
