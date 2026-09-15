package com.aloute.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.util.TimeAgoUtil;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.entity.ChatGroup;
import com.aloute.model.entity.GroupReadStatus;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;

@Service
public class ChatService {

    private final IChatMessageRepository chatMessageRepository;
    private final IUserRepository userRepository;
    private final IGroupReadStatusRepository groupReadStatusRepository;
    private final GroupService groupService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FriendService friendService;
    private final PresenceService presenceService;

    public ChatService(
            IChatMessageRepository chatMessageRepository,
            IUserRepository userRepository,
            IGroupReadStatusRepository groupReadStatusRepository,
            GroupService groupService,
            SimpMessagingTemplate messagingTemplate,
            FriendService friendService,
            PresenceService presenceService) {
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.groupReadStatusRepository = groupReadStatusRepository;
        this.groupService = groupService;
        this.messagingTemplate = messagingTemplate;
        this.friendService = friendService;
        this.presenceService = presenceService;
    }

    // ================= CHAT 1-1 =================

    /**
     * Lưu tin nhắn vào MongoDB rồi đẩy real-time tới người nhận (nếu đang mở app)
     * qua /user/{username}/queue/messages, đồng thời đẩy lại cho chính người gửi
     * để đồng bộ nếu họ đang mở nhiều tab/thiết bị.
     *
     * Chỉ cho phép nhắn tin giữa 2 người đã follow lẫn nhau ("bạn bè") — khớp với
     * yêu cầu: theo dõi lẫn nhau xong mới xuất hiện/nhắn được trong phần tin nhắn.
     */
    public ChatMessageResponse sendMessage(Long senderId, Long receiverId, String content, String clientId) {
        return sendMessage(senderId, receiverId, content, clientId, null, null, null, null);
    }

    public ChatMessageResponse sendMessage(Long senderId, Long receiverId, String content, String clientId, String mediaUrl, String mediaType, String mediaName, Long mediaSize) {
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        if (senderId.equals(receiverId)) {
            throw new BadRequestException("Không thể tự nhắn tin cho chính mình");
        }
        if (!friendService.isFriend(senderId, receiverId)) {
            throw new BadRequestException("Hai người cần theo dõi lẫn nhau (trở thành bạn bè) mới có thể nhắn tin");
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người gửi"));
        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người nhận"));

        ChatMessage message = new ChatMessage();
        message.setConversationId(ChatMessage.buildConversationId(String.valueOf(senderId), String.valueOf(receiverId)));
        message.setSenderId(String.valueOf(senderId));
        message.setReceiverId(String.valueOf(receiverId));
        message.setContent(content == null ? "" : content.trim());
        message.setMediaUrl(mediaUrl);
        message.setMediaType(mediaType);
        message.setMediaName(mediaName);
        message.setMediaSize(mediaSize);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved);
        dto.setClientId(clientId);

        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
        messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", dto);

        return dto;
    }

    /** Lịch sử tin nhắn giữa 2 người, cũ -> mới; mặc định lấy 50 tin gần nhất. */
    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId) {
        return getMessages(currentUserId, otherUserId, 0);
    }

    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId, int page) {
        String conversationId = ChatMessage.buildConversationId(String.valueOf(currentUserId), String.valueOf(otherUserId));
        return history(conversationId, page);
    }

    /**
     * Đánh dấu toàn bộ tin nhắn CỦA otherUserId gửi cho currentUserId là đã đọc,
     * đồng thời báo real-time ngược lại cho otherUserId (nếu đang mở app) để họ
     * thấy bubble mình vừa gửi chuyển thành "Đã xem" ngay lập tức, không cần tải lại.
     */
    public void markAsRead(Long currentUserId, Long otherUserId) {
        List<ChatMessage> unread = chatMessageRepository.findByReceiverIdAndSenderIdAndReadFalse(
                String.valueOf(currentUserId), String.valueOf(otherUserId));
        if (unread.isEmpty()) {
            return;
        }
        unread.forEach(m -> m.setRead(true));
        chatMessageRepository.saveAll(unread);

        userRepository.findById(otherUserId).ifPresent(otherUser -> messagingTemplate.convertAndSendToUser(
                otherUser.getUsername(), "/queue/read-receipts",
                Map.of("readerId", currentUserId, "readAt", LocalDateTime.now().toString())));
    }

    /**
     * Tạo một "tin nhắn hệ thống" đại diện cho cuộc gọi nhỡ.
     * Nó được lưu cùng collection chat_messages để xuất hiện trong lịch sử chat
     * và tự động trở thành mục mới nhất của hội thoại.
     */
    public ChatMessageResponse createMissedCallMessage(Long callerId, Long receiverId, String callId, String callType) {
        if (callerId == null || receiverId == null || callId == null || callId.isBlank()) {
            return null;
        }

        // Idempotent: timeout/retry không tạo 2 dòng cuộc gọi nhỡ.
        var existed = chatMessageRepository.findByCallId(callId);
        if (existed.isPresent()) {
            return toResponse(existed.get());
        }

        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người gọi"));
        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người nhận"));

        ChatMessage message = new ChatMessage();
        message.setConversationId(ChatMessage.buildConversationId(String.valueOf(callerId), String.valueOf(receiverId)));
        message.setSenderId(String.valueOf(callerId));
        message.setReceiverId(String.valueOf(receiverId));
        message.setContent("");
        message.setMessageType("CALL");
        message.setCallId(callId);
        message.setCallType("audio".equalsIgnoreCase(callType) ? "audio" : "video");
        message.setCallStatus("MISSED");
        // Người nhận chưa xem dòng cuộc gọi nhỡ thì badge/unread vẫn hoạt động như tin nhắn.
        message.setRead(false);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved);

        // Cả hai phía thấy cùng một dòng trong lịch sử; phía nhận sẽ có unread nếu chat chưa mở.
        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
        messagingTemplate.convertAndSendToUser(caller.getUsername(), "/queue/messages", dto);
        return dto;
    }

    public long countUnreadTotal(Long userId) {
        long directUnread = chatMessageRepository.countByReceiverIdAndReadFalse(String.valueOf(userId));
        long groupUnread = groupService.getMyGroups(userId).stream()
                .mapToLong(g -> countGroupUnread(g.getId(), userId))
                .sum();
        return directUnread + groupUnread;
    }

    // ================= CHAT NHÓM =================

    /** Gửi tin nhắn nhóm — chỉ thành viên nhóm mới gửi được, đẩy real-time tới TẤT CẢ thành viên. */
    public ChatMessageResponse sendGroupMessage(Long senderId, Long groupId, String content, String clientId) {
        return sendGroupMessage(senderId, groupId, content, clientId, null, null, null, null);
    }

    public ChatMessageResponse sendGroupMessage(Long senderId, Long groupId, String content, String clientId, String mediaUrl, String mediaType) {
        return sendGroupMessage(senderId, groupId, content, clientId, mediaUrl, mediaType, null, null);
    }

    public ChatMessageResponse sendGroupMessage(Long senderId, Long groupId, String content, String clientId, String mediaUrl, String mediaType, String mediaName, Long mediaSize) {
        if ((content == null || content.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new BadRequestException("Tin nhắn phải có nội dung hoặc tệp đính kèm");
        }
        if (!groupService.isMember(groupId, senderId)) {
            throw new UnauthorizedException("Bạn không phải thành viên của nhóm này");
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người gửi"));

        ChatMessage message = new ChatMessage();
        message.setConversationId(ChatMessage.buildGroupConversationId(groupId));
        message.setSenderId(String.valueOf(senderId));
        message.setGroupId(groupId);
        message.setContent(content == null ? "" : content.trim());
        message.setMediaUrl(mediaUrl);
        message.setMediaType(mediaType);
        message.setMediaName(mediaName);
        message.setMediaSize(mediaSize);

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved);
        dto.setClientId(clientId);

        // Đẩy tới TẤT CẢ thành viên nhóm (kể cả người gửi, để đồng bộ nhiều tab/thiết bị)
        for (Long memberId : groupService.getMemberIds(groupId)) {
            userRepository.findById(memberId).ifPresent(
                    u -> messagingTemplate.convertAndSendToUser(u.getUsername(), "/queue/messages", dto));
        }

        return dto;
    }

    /** Lịch sử tin nhắn trong nhóm, cũ -> mới; mặc định lấy 50 tin gần nhất. */
    public List<ChatMessageResponse> getGroupMessages(Long groupId, Long currentUserId) {
        return getGroupMessages(groupId, currentUserId, 0);
    }

    public List<ChatMessageResponse> getGroupMessages(Long groupId, Long currentUserId, int page) {
        if (!groupService.isMember(groupId, currentUserId)) {
            throw new UnauthorizedException("Bạn không phải thành viên của nhóm này");
        }
        String conversationId = ChatMessage.buildGroupConversationId(groupId);
        return history(conversationId, page);
    }

    /** Cập nhật mốc "đã đọc đến lúc này" của user trong nhóm — dùng để tính lại unreadCount. */
    public void markGroupAsRead(Long groupId, Long userId) {
        GroupReadStatus status = groupReadStatusRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseGet(() -> new GroupReadStatus(groupId, userId));
        status.setLastReadAt(LocalDateTime.now());
        groupReadStatusRepository.save(status);
    }

    private long countGroupUnread(Long groupId, Long userId) {
        String conversationId = ChatMessage.buildGroupConversationId(groupId);
        return groupReadStatusRepository.findByGroupIdAndUserId(groupId, userId)
                .map(status -> chatMessageRepository.countByConversationIdAndSentAtAfter(conversationId, status.getLastReadAt()))
                .orElseGet(() -> chatMessageRepository.countByConversationId(conversationId));
    }

    // ================= DANH SÁCH HỘI THOẠI HỢP NHẤT (1-1 + nhóm) =================

    /**
     * Gộp chung hội thoại 1-1 và nhóm vào 1 danh sách duy nhất cho cột trái
     * trang /chat, sắp theo thời điểm tin nhắn mới nhất trước.
     */
    public List<ConversationResponse> getConversations(Long userId) {
        String userIdStr = String.valueOf(userId);
        List<ChatMessage> all = chatMessageRepository.findBySenderIdOrReceiverIdOrderBySentAtDesc(userIdStr, userIdStr);

        Map<String, ChatMessage> latestDirectByConversation = new LinkedHashMap<>();
        for (ChatMessage m : all) {
            if (m.getGroupId() == null) {
                latestDirectByConversation.putIfAbsent(m.getConversationId(), m);
            }
        }

        List<ConversationResponse> result = latestDirectByConversation.values().stream()
                .map(latest -> toDirectConversationResponse(userIdStr, latest))
                .collect(Collectors.toList());

        // Bạn bè (đã follow lẫn nhau) nhưng CHƯA từng nhắn tin — vẫn hiện sẵn trong
        // danh sách để bắt đầu trò chuyện ngay, đúng yêu cầu "follow nhau xong thành bạn bè".
        Set<Long> otherIdsWithConversation = result.stream()
                .map(ConversationResponse::getOtherUserId)
                .collect(Collectors.toSet());

        for (Long friendId : friendService.getMutualFriendIds(userId)) {
            if (!otherIdsWithConversation.contains(friendId)) {
                result.add(toFriendPreviewResponse(friendId));
            }
        }

        for (ChatGroup group : groupService.getMyGroups(userId)) {
            result.add(toGroupConversationResponse(group, userId));
        }

        result.sort((a, b) -> {
            LocalDateTime ta = a.getLastMessageAt();
            LocalDateTime tb = b.getLastMessageAt();
            if (ta == null && tb == null) {
                return nameOf(a).compareToIgnoreCase(nameOf(b));
            }
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return result;
    }

    private String nameOf(ConversationResponse c) {
        String name = c.isGroup() ? c.getGroupName() : c.getOtherFullName();
        return name != null ? name : "";
    }

    private ConversationResponse toDirectConversationResponse(String currentUserIdStr, ChatMessage latest) {
        boolean iAmSender = latest.getSenderId().equals(currentUserIdStr);
        String otherIdStr = iAmSender ? latest.getReceiverId() : latest.getSenderId();
        Long otherId = Long.valueOf(otherIdStr);

        ConversationResponse dto = new ConversationResponse();
        dto.setGroup(false);
        dto.setOtherUserId(otherId);
        dto.setLastMessage(previewText(latest));
        dto.setLastMessageAt(latest.getSentAt());
        dto.setLastMessageTimeAgo(TimeAgoUtil.format(latest.getSentAt()));
        dto.setUnreadCount(chatMessageRepository.countByReceiverIdAndSenderIdAndReadFalse(currentUserIdStr, otherIdStr));
        dto.setOnline(presenceService.isOnline(otherId));

        userRepository.findById(otherId).ifPresent(u -> {
            dto.setOtherUsername(u.getUsername());
            dto.setOtherFullName(u.getFullName());
            dto.setOtherAvatarUrl(u.getAvatarUrl());
        });

        return dto;
    }

    /** Bạn bè chưa từng nhắn tin — hiện trong danh sách với "lastMessage" rỗng, mời bắt đầu trò chuyện. */
    private ConversationResponse toFriendPreviewResponse(Long friendId) {
        ConversationResponse dto = new ConversationResponse();
        dto.setGroup(false);
        dto.setOtherUserId(friendId);
        dto.setHasConversation(false);
        dto.setUnreadCount(0);
        dto.setOnline(presenceService.isOnline(friendId));

        userRepository.findById(friendId).ifPresent(u -> {
            dto.setOtherUsername(u.getUsername());
            dto.setOtherFullName(u.getFullName());
            dto.setOtherAvatarUrl(u.getAvatarUrl());
        });

        return dto;
    }

    private ConversationResponse toGroupConversationResponse(ChatGroup group, Long currentUserId) {
        String conversationId = ChatMessage.buildGroupConversationId(group.getId());

        ConversationResponse dto = new ConversationResponse();
        dto.setGroup(true);
        dto.setGroupId(group.getId());
        dto.setGroupName(group.getName());
        dto.setOtherAvatarUrl(group.getAvatarUrl());
        dto.setMemberCount(groupService.getMemberCount(group.getId()));
        dto.setUnreadCount(countGroupUnread(group.getId(), currentUserId));

        chatMessageRepository.findFirstByConversationIdOrderBySentAtDesc(conversationId).ifPresentOrElse(latest -> {
            dto.setLastMessage(previewText(latest));
            dto.setLastMessageAt(latest.getSentAt());
            dto.setLastMessageTimeAgo(TimeAgoUtil.format(latest.getSentAt()));
        }, () -> {
            dto.setLastMessage("Nhóm vừa được tạo");
            dto.setLastMessageAt(group.getCreatedAt());
            dto.setLastMessageTimeAgo(TimeAgoUtil.format(group.getCreatedAt()));
        });

        return dto;
    }

    private String previewText(ChatMessage message) {
        if ("CALL".equalsIgnoreCase(message.getMessageType())) {
            String call = "audio".equalsIgnoreCase(message.getCallType()) ? "Cuộc gọi thoại" : "Cuộc gọi video";
            if ("MISSED".equalsIgnoreCase(message.getCallStatus())) return "☎ " + call + " nhỡ";
            if ("REJECTED".equalsIgnoreCase(message.getCallStatus())) return "☎ " + call + " bị từ chối";
            return "☎ " + call;
        }
        if (message.getMediaType() != null) {
            if ("video".equalsIgnoreCase(message.getMediaType())) return "🎥 Video";
            if ("file".equalsIgnoreCase(message.getMediaType())) return "📎 " + (message.getMediaName() != null ? message.getMediaName() : "Tệp đính kèm");
            return "📷 Ảnh";
        }
        return message.getContent() != null ? message.getContent() : "";
    }

    private List<ChatMessageResponse> history(String conversationId, int page) {
        int safePage = Math.max(0, page);
        var messages = new java.util.ArrayList<>(chatMessageRepository.findByConversationIdOrderBySentAtDesc(
                conversationId, PageRequest.of(safePage, 50, Sort.by(Sort.Direction.DESC, "sentAt", "id"))));
        java.util.Collections.reverse(messages);

        var senderIds = messages.stream()
                .map(ChatMessage::getSenderId)
                .filter(java.util.Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        var users = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, java.util.function.Function.identity()));

        return messages.stream()
                .map(m -> toResponse(m, users.get(m.getSenderId() != null ? Long.valueOf(m.getSenderId()) : null)))
                .collect(Collectors.toList());
    }

    private ChatMessageResponse toResponse(ChatMessage m) {
        User sender = m.getSenderId() == null ? null
                : userRepository.findById(Long.valueOf(m.getSenderId())).orElse(null);
        return toResponse(m, sender);
    }

    private ChatMessageResponse toResponse(ChatMessage m, User sender) {
        ChatMessageResponse dto = new ChatMessageResponse();
        dto.setId(m.getId());
        dto.setSenderId(Long.valueOf(m.getSenderId()));
        dto.setReceiverId(m.getReceiverId() != null ? Long.valueOf(m.getReceiverId()) : null);
        dto.setGroupId(m.getGroupId());
        dto.setContent(m.getContent());
        dto.setMediaUrl(m.getMediaUrl());
        dto.setMediaType(m.getMediaType());
        dto.setMediaName(m.getMediaName());
        dto.setMediaSize(m.getMediaSize());
        dto.setMessageType(m.getMessageType());
        dto.setCallId(m.getCallId());
        dto.setCallType(m.getCallType());
        dto.setCallStatus(m.getCallStatus());
        dto.setRead(m.isRead());
        dto.setSentAt(m.getSentAt() != null ? m.getSentAt() : LocalDateTime.now());
        dto.setTimeAgo(TimeAgoUtil.format(m.getSentAt()));

        if (sender != null) {
            dto.setSenderUsername(sender.getUsername());
            dto.setSenderFullName(sender.getFullName());
            dto.setSenderAvatarUrl(sender.getAvatarUrl());
        }
        return dto;
    }
}