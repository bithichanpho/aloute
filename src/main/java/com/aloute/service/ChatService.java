package com.aloute.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.entity.User;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.IUserRepository;

@Service
public class ChatService {

    private final IChatMessageRepository chatMessageRepository;
    private final IUserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatService(
            IChatMessageRepository chatMessageRepository,
            IUserRepository userRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Lưu tin nhắn vào MongoDB rồi đẩy real-time tới người nhận (nếu đang mở app)
     * qua /user/{username}/queue/messages, đồng thời đẩy lại cho chính người gửi
     * để đồng bộ nếu họ đang mở nhiều tab/thiết bị.
     */
    public ChatMessageResponse sendMessage(Long senderId, Long receiverId, String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Nội dung tin nhắn không được để trống");
        }
        if (senderId.equals(receiverId)) {
            throw new BadRequestException("Không thể tự nhắn tin cho chính mình");
        }

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người gửi"));
        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người nhận"));

        ChatMessage message = new ChatMessage();
        message.setConversationId(ChatMessage.buildConversationId(String.valueOf(senderId), String.valueOf(receiverId)));
        message.setSenderId(String.valueOf(senderId));
        message.setReceiverId(String.valueOf(receiverId));
        message.setContent(content.trim());

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse dto = toResponse(saved);

        // Đẩy cho người nhận (nếu họ đang mở /chat, chat.js sẽ append ngay không cần F5)
        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", dto);
        // Đẩy lại cho chính người gửi để xác nhận đã lưu thành công (đúng như thiết kế
        // ban đầu trong chat.js: "chỉ hiện lên khung chat SAU KHI server xác nhận")
        messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", dto);

        return dto;
    }

    /** Lịch sử tin nhắn giữa 2 người, cũ -> mới. */
    public List<ChatMessageResponse> getMessages(Long currentUserId, Long otherUserId) {
        String conversationId = ChatMessage.buildConversationId(String.valueOf(currentUserId), String.valueOf(otherUserId));
        return chatMessageRepository.findByConversationIdOrderBySentAtAsc(conversationId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Danh sách hội thoại của 1 user, sắp theo tin nhắn mới nhất trước
     * (dùng cho cột trái trang /chat). Gom nhóm theo conversationId, mỗi
     * nhóm chỉ lấy tin nhắn mới nhất làm "preview".
     */
    public List<ConversationResponse> getConversations(Long userId) {
        String userIdStr = String.valueOf(userId);
        List<ChatMessage> all = chatMessageRepository.findBySenderIdOrReceiverIdOrderBySentAtDesc(userIdStr, userIdStr);

        Map<String, ChatMessage> latestByConversation = new LinkedHashMap<>();
        for (ChatMessage m : all) {
            latestByConversation.putIfAbsent(m.getConversationId(), m);
        }

        return latestByConversation.values().stream()
                .map(latest -> toConversationResponse(userIdStr, latest))
                .collect(Collectors.toList());
    }

    /** Đánh dấu toàn bộ tin nhắn CỦA otherUserId gửi cho currentUserId là đã đọc. */
    public void markAsRead(Long currentUserId, Long otherUserId) {
        List<ChatMessage> unread = chatMessageRepository.findByReceiverIdAndSenderIdAndReadFalse(
                String.valueOf(currentUserId), String.valueOf(otherUserId));
        unread.forEach(m -> m.setRead(true));
        chatMessageRepository.saveAll(unread);
    }

    public long countUnreadTotal(Long userId) {
        return chatMessageRepository.countByReceiverIdAndReadFalse(String.valueOf(userId));
    }

    private ConversationResponse toConversationResponse(String currentUserIdStr, ChatMessage latest) {
        boolean iAmSender = latest.getSenderId().equals(currentUserIdStr);
        String otherIdStr = iAmSender ? latest.getReceiverId() : latest.getSenderId();
        Long otherId = Long.valueOf(otherIdStr);

        ConversationResponse dto = new ConversationResponse();
        dto.setOtherUserId(otherId);
        dto.setLastMessage(latest.getContent());
        dto.setLastMessageAt(latest.getSentAt());
        dto.setUnreadCount(chatMessageRepository.countByReceiverIdAndSenderIdAndReadFalse(currentUserIdStr, otherIdStr));

        userRepository.findById(otherId).ifPresent(u -> {
            dto.setOtherUsername(u.getUsername());
            dto.setOtherFullName(u.getFullName());
            dto.setOtherAvatarUrl(u.getAvatarUrl());
        });

        return dto;
    }

    private ChatMessageResponse toResponse(ChatMessage m) {
        ChatMessageResponse dto = new ChatMessageResponse();
        dto.setId(m.getId());
        dto.setSenderId(Long.valueOf(m.getSenderId()));
        dto.setReceiverId(Long.valueOf(m.getReceiverId()));
        dto.setContent(m.getContent());
        dto.setRead(m.isRead());
        dto.setSentAt(m.getSentAt() != null ? m.getSentAt() : LocalDateTime.now());
        return dto;
    }
}