package com.aloute.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.aloute.dto.response.NotificationResponse;
import com.aloute.model.document.Post;
import com.aloute.model.entity.Notification;
import com.aloute.model.entity.User;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IUserRepository;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final INotificationRepository notificationRepository;
    private final IUserRepository userRepository;
    private final IPostRepository postRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(
            INotificationRepository notificationRepository,
            IUserRepository userRepository,
            IPostRepository postRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Tạo thông báo + đẩy real-time ngay tới người nhận qua WebSocket (nếu đang mở app).
     * "content" chỉ cần mô tả hành động (vd: "đã thích bài viết của bạn") — tên người
     * thực hiện được tự động thêm vào phía trước dựa theo senderId, để LikeService,
     * CommentService, FriendService... không phải tự query User chỉ để lấy username.
     */
    /**
     * Dùng riêng cho LIKE: trước đây LikeService phải tự query Post (thêm 1 round-trip Mongo
     * đồng bộ) CHỈ để lấy userId của chủ bài viết rồi mới gọi notify() — khiến nút thả tym
     * phải chờ thêm cả bước đó mới trả response. Giờ chuyển việc lấy Post vào LUÔN bên trong
     * tác vụ nền (@Async) này, để request like/unlike chỉ còn đúng các bước ghi dữ liệu cần thiết.
     */
    @Async("notificationExecutor")
    public void notifyPostLiked(String postId, Long likerId) {
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) return;

        Long ownerId = Long.valueOf(post.getUserId());
        // notify() cũng @Async nhưng gọi qua "this" (self-invocation) nên chạy thẳng,
        // không tạo thêm task nền mới — ta đã đang ở thread nền rồi.
        notify(ownerId, likerId, "LIKE", postId, "đã thích bài viết của bạn");
    }

    @Async("notificationExecutor")
    public void notify(Long receiverId, Long senderId, String type, String referenceId, String content) {
        if (receiverId.equals(senderId)) return; // không tự thông báo cho chính mình

        // Chạy nền: like/comment/follow không còn phải chờ tạo notification + đẩy
        // WebSocket rồi mới trả response, nên các nút tương tác phản hồi ngay lập tức.
        try {
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
        } catch (Exception e) {
            // Chạy nền nên không có ai "catch" được lỗi này nữa — chỉ log lại,
            // tuyệt đối không để lỗi tạo notification làm hỏng thao tác chính (like/comment/follow).
            log.warn("Gửi notification thất bại (receiverId={}, type={}, error={})", receiverId, type, e.getClass().getSimpleName());
        }
    }

    public List<NotificationResponse> getMyNotifications(Long userId) {
        return notificationRepository.findByReceiverIdOrderByCreatedAtDesc(userId, org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public long countUnread(Long userId) {
        return notificationRepository.countByReceiverIdAndReadFalse(userId);
    }

    public void markAllAsRead(Long userId) {
        notificationRepository.markAllRead(userId);
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
