package com.aloute.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.aloute.dto.response.NotificationResponse;
import com.aloute.model.entity.Notification;
import com.aloute.model.entity.User;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IUserRepository;

@Service
public class NotificationService {

    private final INotificationRepository notificationRepository;
    private final IUserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(
            INotificationRepository notificationRepository,
            IUserRepository userRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Tạo thông báo + đẩy real-time ngay tới người nhận qua WebSocket (nếu đang mở app).
     * "content" chỉ cần mô tả hành động (vd: "đã thích bài viết của bạn") — tên người
     * thực hiện được tự động thêm vào phía trước dựa theo senderId, để LikeService,
     * CommentService, FriendService... không phải tự query User chỉ để lấy username.
     */
    public void notify(Long receiverId, Long senderId, String type, String referenceId, String content) {
        if (receiverId.equals(senderId)) return; // không tự thông báo cho chính mình

        String actorName = userRepository.findById(senderId).map(User::getUsername).orElse("Ai đó");

        Notification notification = new Notification();
        notification.setReceiverId(receiverId);
        notification.setSenderId(senderId);
        notification.setType(type);
        notification.setReferenceId(referenceId);
        notification.setContent(actorName + " " + content);

        Notification saved = notificationRepository.save(notification);

        userRepository.findById(receiverId).ifPresent((User receiver) -> {
            messagingTemplate.convertAndSendToUser(
                    receiver.getUsername(),
                    "/queue/notifications",
                    toResponse(saved)
            );
        });
    }

    public List<NotificationResponse> getMyNotifications(Long userId) {
        return notificationRepository.findByReceiverIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public long countUnread(Long userId) {
        return notificationRepository.countByReceiverIdAndReadFalse(userId);
    }

    public void markAllAsRead(Long userId) {
        List<Notification> list = notificationRepository.findByReceiverIdOrderByCreatedAtDesc(userId);
        list.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(list);
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