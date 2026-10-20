package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.dto.response.NotificationCursorPage;
import com.aloute.dto.response.NotificationResponse;
import com.aloute.model.document.Post;
import com.aloute.model.entity.Notification;
import com.aloute.model.entity.NotificationOutboxEvent;
import com.aloute.model.entity.User;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ContentAccessService access;
    private final INotificationRepository notificationRepository;
    private final IUserRepository userRepository;
    private final IPostRepository postRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PushNotificationService pushNotificationService;
    private final NotificationOutboxService outboxService;

    public NotificationService(
            INotificationRepository notificationRepository,
            IUserRepository userRepository,
            IPostRepository postRepository,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access,
            PushNotificationService pushNotificationService,
            NotificationOutboxService outboxService) {
        this.access = access;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.messagingTemplate = messagingTemplate;
        this.pushNotificationService = pushNotificationService;
        this.outboxService = outboxService;
    }

    public void notifyPostLiked(String postId, Long likerId) {
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) return;
        notify(Long.valueOf(post.getUserId()), likerId, "LIKE", postId,
                "đã thích bài viết của bạn");
    }

    /**
     * Persists a delivery intent instead of calling WebSocket/FCM inline. When invoked from a JPA
     * transaction this write participates in that transaction; otherwise repository.save starts a
     * short transaction of its own. The worker provides retry and idempotency.
     */
    public void notify(
            Long receiverId, Long senderId, String type, String referenceId, String content) {
        if (receiverId == null || senderId == null || receiverId.equals(senderId)
                || !access.profileVisible(receiverId, senderId)
                || !access.profileVisible(senderId, receiverId)) return;
        try {
            outboxService.enqueue(receiverId, senderId, type, referenceId, content);
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not persist notification outbox event (receiverId={}, type={}, cause={})",
                    receiverId, type, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Called by the durable worker; outboxEventId makes the SQL insert retry-safe. */
    void deliverQueued(NotificationOutboxEvent event) {
        if (event.getReceiverId().equals(event.getSenderId())
                || !access.profileVisible(event.getReceiverId(), event.getSenderId())
                || !access.profileVisible(event.getSenderId(), event.getReceiverId())) return;

        String actorName = userRepository.findById(event.getSenderId())
                .map(User::getUsername).orElse("Ai đó");
        Notification saved = notificationRepository.findByOutboxEventId(event.getId())
                .orElseGet(() -> {
                    Notification notification = new Notification();
                    notification.setOutboxEventId(event.getId());
                    notification.setReceiverId(event.getReceiverId());
                    notification.setSenderId(event.getSenderId());
                    notification.setType(event.getNotificationType());
                    notification.setReferenceId(event.getReferenceId());
                    notification.setContent(actorName + " " + event.getContent());
                    return notificationRepository.save(notification);
                });

        // Let transient Firebase failures keep the event in the outbox for retry. The database
        // notification remains idempotent because it is keyed by outboxEventId. Send the realtime
        // event only after Firebase accepts the push; otherwise every retry would duplicate the
        // WebSocket notification for an online receiver.
        pushNotificationService.sendNotificationToUserReliably(
                event.getReceiverId(), "ALOUTE", saved.getContent(), "/activity");
        userRepository.findById(event.getReceiverId()).ifPresent(receiver ->
                messagingTemplate.convertAndSendToUser(
                        receiver.getUsername(), "/queue/notifications", toResponse(saved)));
    }

    public List<NotificationResponse> getMyNotifications(Long userId) {
        var notifications = notificationRepository.findByReceiverIdOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 50));
        var visible = access.visibleProfileIds(
                userId, notifications.stream().map(Notification::getSenderId).toList());
        return notifications.stream()
                .filter(n -> visible.contains(n.getSenderId()))
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
                userId, notifications.stream().map(Notification::getSenderId).toList());
        List<NotificationResponse> items = notifications.stream()
                .filter(n -> visible.contains(n.getSenderId()))
                .map(this::toResponse)
                .toList();
        Long nextCursor = hasMore && !notifications.isEmpty()
                ? notifications.get(notifications.size() - 1).getId()
                : null;
        return new NotificationCursorPage(items, nextCursor, hasMore);
    }

    public long countUnread(Long userId) {
        return notificationRepository.countByReceiverIdAndReadFalse(userId);
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
