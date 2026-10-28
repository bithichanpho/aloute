package com.aloute.notifications.api;

import com.aloute.dto.response.NotificationCursorPage;
import com.aloute.dto.response.NotificationResponse;
import java.util.List;

/** Public in-process use cases; actor IDs must originate from server-side authorization. */
public interface Notifications {
    void notify(Long receiverId, Long senderId, String type, String referenceId, String content);
    /** Durable notice sent to the original requester when a private-profile follow request is accepted. */
    void notifyFollowRequestAccepted(Long requesterId, Long acceptedByUserId);
    void notifyPostLiked(String postId, Long likerId);
    /** Durable system notice for a post author when automatic/admin moderation restricts a post. */
    void notifyPostModerationResult(Long authorId, String postId, com.aloute.model.document.ModerationStatus status);
    /** Durable alert for a community owner/manager when a community post is reported. */
    void notifyCommunityPostReported(Long receiverId, String postId);
    /** Durable alert that routes system administrators to the formal moderation report queue. */
    void notifyAdminCommunityReportOpened(Long receiverId, String reportId);
    List<NotificationResponse> getMyNotifications(Long userId);
    NotificationCursorPage getMyNotifications(Long userId, Long beforeId, int size);
    long countUnread(Long userId);
    void markAllAsRead(Long userId);
    void markAsRead(Long userId, Long notificationId);
    void delete(Long userId, Long notificationId);
}
