package com.aloute.notifications.api;

import com.aloute.dto.response.NotificationCursorPage;
import com.aloute.dto.response.NotificationResponse;
import java.util.List;

/** Public in-process use cases; actor IDs must originate from server-side authorization. */
public interface Notifications {
    void notify(Long receiverId, Long senderId, String type, String referenceId, String content);
    void notifyPostLiked(String postId, Long likerId);
    void notifyFollowRequestAccepted(Long requesterId, Long acceptedByUserId);
    /** Status is an immutable boundary value; callers retain ownership of their domain enum. */
    void notifyPostModerationResult(Long authorId, String postId, String status);
    List<NotificationResponse> getMyNotifications(Long userId);
    NotificationCursorPage getMyNotifications(Long userId, Long beforeId, int size);
    long countUnread(Long userId);
    void markAllAsRead(Long userId);
    void markAsRead(Long userId, Long notificationId);
    void delete(Long userId, Long notificationId);
}
