package com.aloute.controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.SendMessageRequest;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.repository.IUserRepository;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ChatService;
import com.aloute.service.PresenceService;

/**
 * Tin nhắn có 2 đường vào:
 *  - REST (/api/chat/**): lấy lịch sử, danh sách hội thoại, đánh dấu đã đọc — gọi
 *    lúc tải trang /chat hoặc chuyển hội thoại (không cần real-time cho việc này).
 *  - STOMP (/app/chat.send): gửi tin nhắn mới, xử lý real-time 2 chiều, xem
 *    WebSocketConfig để biết cách xác thực phiên WebSocket bằng cookie JWT.
 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final IUserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceService presenceService;

    public ChatController(ChatService chatService, IUserRepository userRepository,
            SimpMessagingTemplate messagingTemplate, PresenceService presenceService) {
        this.chatService = chatService;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
        this.presenceService = presenceService;
    }

    @GetMapping("/api/chat/conversations")
    public ApiResponse<List<ConversationResponse>> getConversations(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getConversations(userId));
    }

    @GetMapping("/api/chat/messages/{otherUserId}")
    public ApiResponse<List<ChatMessageResponse>> getMessages(
            Authentication authentication, @PathVariable Long otherUserId) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getMessages(userId, otherUserId));
    }

    @PostMapping("/api/chat/read/{otherUserId}")
    public ApiResponse<Void> markAsRead(Authentication authentication, @PathVariable Long otherUserId) {
        Long userId = requireUserId(authentication);
        chatService.markAsRead(userId, otherUserId);
        return ApiResponse.success("Đã đánh dấu đã đọc", null);
    }

    @GetMapping("/api/chat/unread-count")
    public ApiResponse<Map<String, Long>> getUnreadCount(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(Map.of("count", chatService.countUnreadTotal(userId)));
    }

    /** Trạng thái online tức thời của 1 user — dùng để tô chấm xanh khi vừa mở khung chat. */
    @GetMapping("/api/chat/online/{userId}")
    public ApiResponse<Boolean> isOnline(@PathVariable Long userId) {
        return ApiResponse.success(presenceService.isOnline(userId));
    }

    /**
     * Client gửi STOMP frame tới đích /app/chat.send kèm payload
     * {"receiverId": 5, "content": "...", "clientId": "tmp-123"}. "principal" là
     * người dùng đã được WebSocketConfig xác thực từ cookie JWT ngay lúc bắt tay
     * kết nối. "clientId" (tuỳ chọn) được echo nguyên vẹn lại trong response để
     * front-end đối chiếu bubble "đang gửi" hiển thị lạc quan (optimistic UI).
     */
    @MessageMapping("/chat.send")
    public void sendMessage(SendMessageRequest request, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().startsWith("anonymous-")) {
            return; // phiên WebSocket chưa xác thực — bỏ qua, không lưu/gửi gì cả
        }

        userRepository.findByUsername(principal.getName()).ifPresent(sender -> {
            try {
                chatService.sendMessage(sender.getId(), request.getReceiverId(), request.getContent(), request.getClientId());
            } catch (RuntimeException ex) {
                // Báo lỗi ngược lại đúng phiên WS của người gửi (vd: nội dung rỗng, chưa là bạn bè...)
                messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors",
                        Map.of("message", ex.getMessage(), "clientId", request.getClientId() != null ? request.getClientId() : ""));
            }
        });
    }

    /**
     * Báo cho người nhận biết mình đang gõ — payload {"receiverId": 5}. Không lưu
     * gì lại (tin nhắn "đang nhập" chỉ có ý nghĩa tức thời), chỉ tiếp sức tiếp tới
     * đúng người nhận qua /queue/typing kèm senderId để họ biết ai đang gõ.
     */
    @MessageMapping("/chat.typing")
    public void typing(Map<String, Object> payload, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().startsWith("anonymous-")) {
            return;
        }
        Object receiverIdRaw = payload.get("receiverId");
        if (receiverIdRaw == null) {
            return;
        }
        userRepository.findByUsername(principal.getName()).ifPresent(sender -> {
            try {
                Long receiverId = Long.valueOf(receiverIdRaw.toString());
                userRepository.findById(receiverId).ifPresent(receiver -> messagingTemplate.convertAndSendToUser(
                        receiver.getUsername(), "/queue/typing", Map.of("senderId", sender.getId())));
            } catch (NumberFormatException ignored) {
                // payload sai định dạng — bỏ qua, đây chỉ là tín hiệu phụ không quan trọng
            }
        });
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}