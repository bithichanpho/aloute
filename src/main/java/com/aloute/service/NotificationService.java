package com.aloute.service;

import com.aloute.dto.response.NotificationResponse;
import com.aloute.model.document.Post;
import com.aloute.model.entity.Notification;
import com.aloute.model.entity.User;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    private final ContentAccessService access;

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final INotificationRepository notificationRepository;
    private final IUserRepository userRepository;
    private final IPostRepository postRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(
            INotificationRepository notificationRepository,
            IUserRepository userRepository,
            IPostRepository postRepository,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access) {
        this.access = access;

        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Async("notificationExecutor")
    public void notifyPostLiked(String postId, Long likerId) {
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) return;

        Long ownerId = Long.valueOf(post.getUserId());

        notify(ownerId, likerId, "LIKE", postId, "đã thích bài viết của bạn");
    }

    @Async("notificationExecutor")
    public void notify(
            Long receiverId, Long senderId, String type, String referenceId, String content) {
        if (receiverId.equals(senderId)
                || !access.profileVisible(receiverId, senderId)
                || !access.profileVisible(senderId, receiverId)) return;

        try {
            String actorName =
                    userRepository.findById(senderId).map(User::getUsername).orElse("Ai đó");

            Notification notification = new Notification();
            notification.setReceiverId(receiverId);
            notification.setSenderId(senderId);
            notification.setType(type);
            notification.setReferenceId(referenceId);
            notification.setContent(actorName + " " + content);

            Notification saved = notificationRepository.save(notification);

            userRepository
                    .findById(receiverId)
                    .ifPresent(
                            (User receiver) -> {
                                messagingTemplate.convertAndSendToUser(
                                        receiver.getUsername(),
                                        "/queue/notifications",
                                        toResponse(saved));
                            });
        } catch (Exception e) {

            log.warn(
                    "Gửi notification thất bại (receiverId={}, type={}, error={})",
                    receiverId,
                    type,
                    e.getClass().getSimpleName());
        }
    }

    public List<NotificationResponse> getMyNotifications(Long userId) {
        var notifications =
                notificationRepository.findByReceiverIdOrderByCreatedAtDesc(
                        userId, org.springframework.data.domain.PageRequest.of(0, 50));
        var visible =
                access.visibleProfileIds(
                        userId, notifications.stream().map(Notification::getSenderId).toList());
        return notifications.stream()
                .filter(n -> visible.contains(n.getSenderId()))
                .map(this::toResponse)
                .collect(Collectors.toList());
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
                .orElseThrow(() -> new com.aloute.common.exception.ResourceNotFoundException(
                        "Không tìm thấy thông báo"));
        notification.setRead(true);
        notificationRepository.save(notification);
    }

    public void delete(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .filter(item -> userId.equals(item.getReceiverId()))
                .orElseThrow(() -> new com.aloute.common.exception.ResourceNotFoundException(
                        "Không tìm thấy thông báo"));
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
