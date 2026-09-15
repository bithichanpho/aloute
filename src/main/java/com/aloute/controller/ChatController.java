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
import com.aloute.service.FriendService;

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
    private final FriendService friendService;
    private final com.aloute.service.NotificationService notificationService;
    private final com.aloute.service.CallTimeoutService callTimeoutService;

    public ChatController(ChatService chatService, IUserRepository userRepository,
            SimpMessagingTemplate messagingTemplate, PresenceService presenceService, FriendService friendService, com.aloute.service.NotificationService notificationService, com.aloute.service.CallTimeoutService callTimeoutService) {
        this.chatService = chatService;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
        this.presenceService = presenceService;
        this.friendService = friendService;
        this.notificationService = notificationService;
        this.callTimeoutService = callTimeoutService;
    }

    @GetMapping("/api/chat/conversations")
    public ApiResponse<List<ConversationResponse>> getConversations(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getConversations(userId));
    }

    @GetMapping("/api/chat/messages/{otherUserId}")
    public ApiResponse<List<ChatMessageResponse>> getMessages(
            Authentication authentication, @PathVariable Long otherUserId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getMessages(userId, otherUserId, page));
    }

    @PostMapping("/api/chat/read/{otherUserId}")
    public ApiResponse<Void> markAsRead(Authentication authentication, @PathVariable Long otherUserId) {
        Long userId = requireUserId(authentication);
        chatService.markAsRead(userId, otherUserId);
        return ApiResponse.success("Đã đánh dấu đã đọc", null);
    }

    /** Lịch sử tin nhắn trong 1 nhóm — chỉ thành viên nhóm mới xem được. */
    @GetMapping("/api/chat/group/{groupId}/messages")
    public ApiResponse<List<ChatMessageResponse>> getGroupMessages(
            Authentication authentication, @PathVariable Long groupId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getGroupMessages(groupId, userId, page));
    }

    /** Đánh dấu đã đọc hội thoại nhóm đến thời điểm hiện tại. */
    @PostMapping("/api/chat/group/{groupId}/read")
    public ApiResponse<Void> markGroupAsRead(Authentication authentication, @PathVariable Long groupId) {
        Long userId = requireUserId(authentication);
        chatService.markGroupAsRead(groupId, userId);
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
     * {"receiverId": 5, "content": "..."} (chat 1-1) hoặc {"groupId": 2, "content": "..."}
     * (chat nhóm) — kèm "clientId" tùy chọn để front-end đối chiếu bubble "đang gửi"
     * hiển thị lạc quan (optimistic UI). "principal" là người dùng đã được
     * WebSocketConfig xác thực từ cookie JWT ngay lúc bắt tay kết nối.
     */
    @MessageMapping("/chat.send")
    public void sendMessage(SendMessageRequest request, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().startsWith("anonymous-")) {
            return; // phiên WebSocket chưa xác thực — bỏ qua, không lưu/gửi gì cả
        }

        userRepository.findByUsername(principal.getName()).ifPresent(sender -> {
            try {
                if (request.getGroupId() != null) {
                    chatService.sendGroupMessage(sender.getId(), request.getGroupId(), request.getContent(), request.getClientId(), request.getMediaUrl(), request.getMediaType(), request.getMediaName(), request.getMediaSize());
                } else {
                    chatService.sendMessage(sender.getId(), request.getReceiverId(), request.getContent(), request.getClientId(), request.getMediaUrl(), request.getMediaType(), request.getMediaName(), request.getMediaSize());
                }
            } catch (RuntimeException ex) {
                // Báo lỗi ngược lại đúng phiên WS của người gửi (vd: nội dung rỗng, chưa là bạn bè, không phải thành viên nhóm...)
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


    // ================= VIDEO CALL SIGNALING (WebRTC) =================

    @MessageMapping("/call.offer")
    public void callOffer(Map<String, Object> payload, Principal principal) {
        forwardCallSignal(payload, principal, "offer");
    }

    @MessageMapping("/call.answer")
    public void callAnswer(Map<String, Object> payload, Principal principal) {
        forwardCallSignal(payload, principal, "answer");
    }

    @MessageMapping("/call.ice")
    public void callIce(Map<String, Object> payload, Principal principal) {
        forwardCallSignal(payload, principal, "ice");
    }

    @MessageMapping("/call.hangup")
    public void callHangup(Map<String, Object> payload, Principal principal) {
        forwardCallSignal(payload, principal, "hangup");
    }

    private void forwardCallSignal(Map<String, Object> payload, Principal principal, String type) {
        if (principal == null || principal.getName() == null) return;
        Object targetRaw = payload.get("targetUserId");
        if (targetRaw == null) return;
        final Long targetId;
        try {
            targetId = Long.valueOf(targetRaw.toString());
        } catch (NumberFormatException ex) {
            return;
        }

        userRepository.findByUsername(principal.getName()).ifPresent(sender -> {
            if (sender.getId().equals(targetId)) return;
            if (!friendService.isFriend(sender.getId(), targetId)) return;

            userRepository.findById(targetId).ifPresent(target -> {
                Map<String, Object> signal = new java.util.HashMap<>(payload);
                signal.put("type", type);
                signal.put("fromUserId", sender.getId());
                signal.put("fromUsername", sender.getUsername());
                signal.put("fromFullName", sender.getFullName());
                signal.put("fromAvatarUrl", sender.getAvatarUrl());
                signal.put("callType", payload.getOrDefault("callType", "video"));
                messagingTemplate.convertAndSendToUser(target.getUsername(), "/queue/call", signal);

                String callId = String.valueOf(payload.getOrDefault("callId", ""));
                String callType = String.valueOf(payload.getOrDefault("callType", "video"));
                if ("offer".equals(type) && !callId.isBlank()) {
                    // Không đánh dấu miss ngay khi offer được gửi. Cho phép người nhận có đủ 30s để
                    // nhận cuộc gọi. Scheduler sẽ kết thúc + lưu CALL_MISSED nếu không có answer/reject.
                    callTimeoutService.schedule(callId, sender.getId(), targetId, callType);
                } else if ("answer".equals(type)) {
                    callTimeoutService.cancel(callId);
                } else if ("hangup".equals(type)) {
                    callTimeoutService.cancel(callId);
                    boolean missed = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("missed", "false")));
                    Long callerId = null;
                    Object callerRaw = payload.get("callerUserId");
                    if (callerRaw != null) {
                        try { callerId = Long.valueOf(callerRaw.toString()); } catch (NumberFormatException ignored) { }
                    }
                    // Chỉ caller (người khởi tạo) gửi timeout/missed=true mới tạo thông báo CALL_MISSED
                    // cho receiver. Người nhận bấm Từ chối không bị ghi nhầm thành cuộc gọi nhỡ cho caller.
                    if (missed && callerId != null && sender.getId().equals(callerId)) {
                        notificationService.notify(targetId, sender.getId(), "CALL_MISSED", callId,
                                "đã gọi " + ("audio".equalsIgnoreCase(callType) ? "thoại" : "video") + " cho bạn");
                    }
                }
            });
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