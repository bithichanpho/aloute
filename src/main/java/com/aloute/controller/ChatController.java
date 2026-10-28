package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.TooManyRequestsException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.SendMessageRequest;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ChatCursorPage;
import com.aloute.dto.response.ConversationCursorPage;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.service.AuthenticationUserCache;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ChatService;
import com.aloute.service.FriendService;
import com.aloute.service.PresenceService;
import com.aloute.service.ContentAccessService;
import com.aloute.notifications.api.Notifications;
import com.aloute.messaging.api.CallSignaling;
import com.aloute.messaging.api.CallSignaling.Session;
import com.aloute.service.AuthRateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/chat")
@Validated
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    public record TypingSignal(@Positive Long receiverId) {}
    public record DeliverySignal(@NotBlank @Size(max = 100) String messageId) {}

    public record CallSignal(
            @NotNull @Positive Long targetUserId,
            @Positive Long callerUserId,
            @NotBlank @Size(max = 100) String callId,
            @Pattern(regexp = "audio|video") String callType,
            Boolean missed,
            Object offer,
            Object answer,
            Object candidate,
            Object sdp) {}
    private final ContentAccessService access;

    private final ChatService chatService;
    private final AuthenticationUserCache authenticationUserCache;
    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceService presenceService;
    private final FriendService friendService;
    private final Notifications notificationService;
    private final CallSignaling callTimeoutService;
    private final Validator validator;
    private final AuthRateLimitService rateLimitService;

    public ChatController(
            ChatService chatService,
            AuthenticationUserCache authenticationUserCache,
            SimpMessagingTemplate messagingTemplate,
            PresenceService presenceService,
            FriendService friendService,
            Notifications notificationService,
            CallSignaling callTimeoutService,
            ContentAccessService access,
            Validator validator,
            AuthRateLimitService rateLimitService) {
        this.access = access;

        this.chatService = chatService;
        this.authenticationUserCache = authenticationUserCache;
        this.messagingTemplate = messagingTemplate;
        this.presenceService = presenceService;
        this.friendService = friendService;
        this.notificationService = notificationService;
        this.callTimeoutService = callTimeoutService;
        this.validator = validator;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationResponse>> getConversations(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getConversations(userId));
    }

    @GetMapping("/conversations/cursor")
    public ApiResponse<ConversationCursorPage> getConversationsByCursor(
            Authentication authentication,
            @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 512)
                    String cursor,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getConversationPage(userId, cursor, size));
    }

    @GetMapping("/messages/{otherUserId}")
    public ApiResponse<List<ChatMessageResponse>> getMessages(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long otherUserId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") @Max(value = 200, message = "Lịch sử trò chuyện quá sâu (tối đa 200 trang)") int page) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getMessages(userId, otherUserId, page));
    }

    @GetMapping("/messages/{otherUserId}/search")
    public ApiResponse<List<ChatMessageResponse>> searchMessages(
            Authentication authentication,
            @PathVariable @Positive Long otherUserId,
            @RequestParam("q") @NotBlank @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 100) String beforeId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        Long userId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "CHAT_SEARCH", userId.toString(), 60,
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1),
                "Bạn tìm kiếm quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa tìm kiếm do gửi quá nhiều yêu cầu.");
        return ApiResponse.success(chatService.searchDirectMessages(
                userId, otherUserId, keyword, beforeId, size, false));
    }

    @GetMapping("/messages/{otherUserId}/cursor")
    public ApiResponse<ChatCursorPage> getMessagesByCursor(
            Authentication authentication,
            @PathVariable @Positive Long otherUserId,
            @RequestParam(required = false) @Size(max = 100) String beforeId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        Long userId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "CHAT_HISTORY", userId.toString(), 180,
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1),
                "Bạn tải lịch sử quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa tải lịch sử do gửi quá nhiều yêu cầu.");
        return ApiResponse.success(chatService.getMessagesByCursor(userId, otherUserId, beforeId, size));
    }

    @GetMapping("/messages/{otherUserId}/media")
    public ApiResponse<List<ChatMessageResponse>> directMedia(
            Authentication authentication,
            @PathVariable @Positive Long otherUserId,
            @RequestParam(required = false) @Size(max = 100) String beforeId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        return ApiResponse.success(chatService.searchDirectMessages(
                requireUserId(authentication), otherUserId, null, beforeId, size, true));
    }

    @PostMapping("/read/{otherUserId}")
    public ApiResponse<Void> markAsRead(
            Authentication authentication, @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long otherUserId) {
        Long userId = requireUserId(authentication);
        chatService.markAsRead(userId, otherUserId);
        return ApiResponse.success("Đã đánh dấu đã đọc", null);
    }

    @GetMapping("/group/{groupId}/messages")
    public ApiResponse<List<ChatMessageResponse>> getGroupMessages(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã nhóm không hợp lệ") Long groupId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") @Max(value = 200, message = "Lịch sử trò chuyện quá sâu (tối đa 200 trang)") int page) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getGroupMessages(groupId, userId, page));
    }

    @GetMapping("/group/{groupId}/messages/search")
    public ApiResponse<List<ChatMessageResponse>> searchGroupMessages(
            Authentication authentication,
            @PathVariable @Positive Long groupId,
            @RequestParam("q") @NotBlank @Size(max = 100) String keyword,
            @RequestParam(required = false) @Size(max = 100) String beforeId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        Long userId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "CHAT_SEARCH", userId.toString(), 60,
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1),
                "Bạn tìm kiếm quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa tìm kiếm do gửi quá nhiều yêu cầu.");
        return ApiResponse.success(chatService.searchGroupMessages(
                userId, groupId, keyword, beforeId, size, false));
    }

    @GetMapping("/group/{groupId}/messages/cursor")
    public ApiResponse<ChatCursorPage> getGroupMessagesByCursor(
            Authentication authentication,
            @PathVariable @Positive Long groupId,
            @RequestParam(required = false) @Size(max = 100) String beforeId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        Long userId = requireUserId(authentication);
        rateLimitService.checkAndRecord(
                "CHAT_HISTORY", userId.toString(), 180,
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1),
                "Bạn tải lịch sử quá nhanh. Thử lại sau %d phút.",
                "Tạm khóa tải lịch sử do gửi quá nhiều yêu cầu.");
        return ApiResponse.success(chatService.getGroupMessagesByCursor(groupId, userId, beforeId, size));
    }

    @GetMapping("/group/{groupId}/messages/media")
    public ApiResponse<List<ChatMessageResponse>> groupMedia(
            Authentication authentication,
            @PathVariable @Positive Long groupId,
            @RequestParam(required = false) @Size(max = 100) String beforeId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        return ApiResponse.success(chatService.searchGroupMessages(
                requireUserId(authentication), groupId, null, beforeId, size, true));
    }

    @PostMapping("/group/{groupId}/read")
    public ApiResponse<Void> markGroupAsRead(
            Authentication authentication, @PathVariable @Positive(message = "Mã nhóm không hợp lệ") Long groupId) {
        Long userId = requireUserId(authentication);
        chatService.markGroupAsRead(groupId, userId);
        return ApiResponse.success("Đã đánh dấu đã đọc", null);
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> getUnreadCount(Authentication authentication) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(Map.of("count", chatService.countUnreadTotal(userId)));
    }

    @DeleteMapping("/messages/{messageId}")
    public ApiResponse<Void> deleteMessage(
            Authentication authentication,
            @PathVariable @Size(min = 1, max = 100, message = "Mã tin nhắn không hợp lệ") String messageId,
            @RequestParam(defaultValue = "false") boolean forEveryone) {
        chatService.deleteMessage(messageId, requireUserId(authentication), forEveryone);
        return ApiResponse.success(forEveryone ? "Đã thu hồi tin nhắn" : "Đã xóa tin nhắn ở phía bạn", null);
    }

    @GetMapping("/online/{userId}")
    public ApiResponse<Boolean> isOnline(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        Long requesterId = requireUserId(authentication);
        // Presence is private information: only the account itself or a mutual friend can query it.
        if (!requesterId.equals(userId) && !friendService.isFriend(requesterId, userId)) {
            // Return 404 rather than reveal whether a blocked/non-friend account exists or is online.
            throw new ResourceNotFoundException("Không tìm thấy tài khoản");
        }
        return ApiResponse.success(presenceService.isOnline(userId));
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Valid SendMessageRequest request, Principal principal) {
        if (request == null) return;
        if (principal == null
                || principal.getName() == null
                || principal.getName().startsWith("anonymous-")) {
            return;
        }

        authenticationUserCache
                .findByUsername(principal.getName())
                .ifPresent(
                        sender -> {
                            try {
                                var violations = validator.validate(request);
                                if (!violations.isEmpty()) {
                                    throw new BadRequestException(
                                            violations.iterator().next().getMessage());
                                }
                                rateLimitService.checkAndRecord(
                                        "CHAT_MESSAGE", sender.getId().toString(), 120,
                                        java.time.Duration.ofMinutes(5),
                                        java.time.Duration.ofMinutes(5),
                                        "Bạn gửi tin quá nhanh. Thử lại sau %d phút.",
                                        "Tạm khóa gửi tin do gửi quá nhiều yêu cầu.");
                                boolean retryOfPersistedMessage =
                                        chatService.hasMessageForClientId(sender.getId(), request.getClientId());
                                ChatMessageResponse response;
                                if (request.getGroupId() != null) {
                                    response = chatService.sendGroupMessage(
                                            sender.getId(),
                                            request.getGroupId(),
                                            request.getContent(),
                                            request.getClientId(),
                                            request.getMediaUrl(),
                                            request.getMediaType(),
                                            request.getMediaName(),
                                            request.getMediaSize());
                                } else {
                                    response = chatService.sendMessage(
                                            sender.getId(),
                                            request.getReceiverId(),
                                            request.getContent(),
                                            request.getClientId(),
                                            request.getMediaUrl(),
                                            request.getMediaType(),
                                            request.getMediaName(),
                                            request.getMediaSize());
                                }
                                // A client retry gets an ACK with the already-persisted message, but
                                // does not fan it out to every participant a second time.
                                if (retryOfPersistedMessage) {
                                    messagingTemplate.convertAndSendToUser(
                                            principal.getName(), "/queue/messages", response);
                                }
                            } catch (RuntimeException ex) {
                                if (ex instanceof DuplicateKeyException) {
                                    try {
                                        ChatMessageResponse persisted = chatService.findMessageForClientId(
                                                sender.getId(), request.getClientId());
                                        if (persisted != null) {
                                            // Two concurrent tabs raced on the unique (senderId, clientId) index.
                                            // ACK the canonical message only to the sender; never fan it out twice.
                                            messagingTemplate.convertAndSendToUser(
                                                    principal.getName(), "/queue/messages", persisted);
                                            return;
                                        }
                                    } catch (RuntimeException lookupFailure) {
                                        log.debug("Unable to verify idempotent chat retry (senderId={}, cause={})",
                                                sender.getId(), lookupFailure.getClass().getSimpleName());
                                    }
                                }
                                log.warn("WebSocket chat send rejected (senderId={}, cause={})",
                                        sender.getId(), ex.getClass().getSimpleName());
                                Map<String, Object> errorPayload = new java.util.HashMap<>();
                                errorPayload.put("message", ex instanceof TooManyRequestsException
                                        ? "Bạn thao tác quá nhanh. Vui lòng thử lại sau."
                                        : "Không thể gửi tin nhắn. Hãy kiểm tra nội dung, quyền truy cập và thử lại.");
                                errorPayload.put("clientId", request.getClientId() == null ? "" : request.getClientId());
                                messagingTemplate.convertAndSendToUser(
                                        principal.getName(), "/queue/errors", errorPayload);
                            }
                        });
    }

    @MessageMapping("/chat.delivered")
    public void acknowledgeDelivered(@Valid DeliverySignal payload, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().startsWith("anonymous-")) return;
        if (!validator.validate(payload).isEmpty()) return;
        authenticationUserCache.findByUsername(principal.getName()).ifPresent(user -> {
            try {
                if (!user.isActive() || !allowSignal("WS_ACK", user.getId().toString(), 300,
                        java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5))) return;
                chatService.markAsDelivered(user.getId(), payload.messageId());
            } catch (RuntimeException ex) {
                // A duplicate ACK or an already-deleted message must not interrupt the chat socket,
                // but retain a diagnostic so unexpected persistence/authorization failures are visible.
                log.debug(
                        "Delivery acknowledgement rejected (recipientId={}, messageId={}, cause={})",
                        user.getId(), payload.messageId(), ex.getClass().getSimpleName());
            }
        });
    }

    @MessageMapping("/chat.typing")
    public void typing(@Valid TypingSignal payload, Principal principal) {
        if (principal == null
                || principal.getName() == null
                || principal.getName().startsWith("anonymous-")) {
            return;
        }
        if (!validator.validate(payload).isEmpty()) return;
        authenticationUserCache
                .findByUsername(principal.getName())
                .ifPresent(
                        sender -> {
                            try {
                                if (!sender.isActive() || !allowSignal("WS_TYPING", sender.getId().toString(), 60,
                                        java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1))) return;
                                Long receiverId = payload.receiverId();
                                if (!sender.isActive()
                                        || !access.profileVisible(sender.getId(), receiverId)
                                        || !access.canMessageAccount(sender.getId(), receiverId))
                                    return;
                                authenticationUserCache
                                        .findById(receiverId)
                                        .ifPresent(
                                                receiver ->
                                                        messagingTemplate.convertAndSendToUser(
                                                                receiver.getUsername(),
                                                                "/queue/typing",
                                                                Map.of(
                                                                        "senderId",
                                                                        sender.getId())));
                            } catch (RuntimeException ex) {
                                log.debug("Typing signal not delivered (senderId={}, receiverId={}, cause={})",
                                        sender.getId(), payload.receiverId(), ex.getClass().getSimpleName());
                            }
                        });
    }

    @MessageMapping("/call.offer")
    public void callOffer(@Valid CallSignal payload, Principal principal) {
        forwardCallSignal(payload, principal, "offer");
    }

    @MessageMapping("/call.answer")
    public void callAnswer(@Valid CallSignal payload, Principal principal) {
        forwardCallSignal(payload, principal, "answer");
    }

    @MessageMapping("/call.ice")
    public void callIce(@Valid CallSignal payload, Principal principal) {
        forwardCallSignal(payload, principal, "ice");
    }

    @MessageMapping("/call.hangup")
    public void callHangup(@Valid CallSignal payload, Principal principal) {
        forwardCallSignal(payload, principal, "hangup");
    }

    private void forwardCallSignal(CallSignal payload, Principal principal, String type) {
        if (principal == null || principal.getName() == null) return;
        if (!validator.validate(payload).isEmpty()) return;

        final Long targetId = payload.targetUserId();
        final String callId = payload.callId() == null ? "" : payload.callId().trim();
        final String callType = "audio".equalsIgnoreCase(payload.callType()) ? "audio" : "video";
        if (callId.isBlank()) return;

        authenticationUserCache.findByUsername(principal.getName()).ifPresent(sender -> {
            if (sender.getId().equals(targetId) || !sender.isActive()) return;
            boolean peerVisible = access.profileVisible(sender.getId(), targetId)
                    && friendService.isFriend(sender.getId(), targetId);
            // Revocation must not strand an existing call; only its registered participants can end it.
            if (!peerVisible && !"hangup".equals(type)) return;
            if (!allowSignal("WS_CALL_SIGNAL", sender.getId().toString(), 120,
                    java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(1))) return;

            if ("offer".equals(type)) {
                java.util.Optional<? extends Session> existing = callTimeoutService.findCall(callId);
                if (existing.isPresent()) {
                    Session call = existing.get();
                    if (!sender.getId().equals(call.callerId()) || !targetId.equals(call.calleeId())) return;
                } else if (!allowSignal("WS_CALL_OFFER_PAIR", sender.getId() + ":" + targetId, 3,
                        java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10))) {
                    return;
                }
            }

            java.util.Optional<? extends Session> info;
            switch (type) {
                case "offer" -> {
                    // SDP is sent by the current client as `sdp`; callerUserId is never trusted.
                    if (payload.sdp() == null && payload.offer() == null) return;
                    if (!callTimeoutService.registerOffer(callId, sender.getId(), targetId, callType)) return;
                    info = callTimeoutService.findCall(callId);
                }
                case "answer" -> {
                    if (payload.sdp() == null && payload.answer() == null) return;
                    if (!callTimeoutService.confirmAnswer(callId, sender.getId(), targetId)) return;
                    info = callTimeoutService.findCall(callId);
                }
                case "ice" -> {
                    if (!callTimeoutService.authorizePeerSignal(callId, sender.getId(), targetId)) return;
                    info = callTimeoutService.findCall(callId);
                }
                case "hangup" -> info = callTimeoutService.finishCall(callId, sender.getId(), targetId);
                default -> { return; }
            }
            if (info.isEmpty()) return;
            Session call = info.get();
            if (!peerVisible) return; // state cleanup is allowed, private signaling remains suppressed

            authenticationUserCache.findById(targetId).ifPresent(target -> {
                Map<String, Object> signal = new java.util.HashMap<>();
                if (payload.offer() != null) signal.put("offer", payload.offer());
                if (payload.answer() != null) signal.put("answer", payload.answer());
                if (payload.sdp() != null) signal.put("sdp", payload.sdp());
                if (payload.candidate() != null) signal.put("candidate", payload.candidate());
                signal.put("targetUserId", targetId);
                // Server-owned call identity and original caller; ignore spoofed callerUserId from client.
                signal.put("callerUserId", call.callerId());
                signal.put("callId", call.callId());
                signal.put("type", type);
                signal.put("fromUserId", sender.getId());
                signal.put("fromUsername", sender.getUsername());
                signal.put("fromFullName", sender.getFullName());
                signal.put("fromAvatarUrl", sender.getAvatarUrl());
                signal.put("callType", call.callType());
                if ("hangup".equals(type)) {
                    signal.put("missed", "MISSED".equals(call.state()));
                }
                try {
                    messagingTemplate.convertAndSendToUser(target.getUsername(), "/queue/call", signal);
                } catch (RuntimeException unavailable) {
                    log.warn("Call signal delivery failed (callId={}, cause={})", callId,
                            unavailable.getClass().getSimpleName());
                }
                // Terminal outcome/recovery belongs to Calls; client `missed` never enqueues notifications.
            });
        });
    }

    private boolean allowSignal(
            String namespace,
            String subject,
            int maxRequests,
            java.time.Duration window,
            java.time.Duration blockDuration) {
        try {
            rateLimitService.checkAndRecord(
                    namespace,
                    subject,
                    maxRequests,
                    window,
                    blockDuration,
                    "Bạn thao tác quá nhanh. Thử lại sau %d phút.",
                    "Tạm khóa thao tác do gửi quá nhiều tín hiệu.");
            return true;
        } catch (RuntimeException rejected) {
            log.debug("WebSocket signal throttled or limiter unavailable (namespace={}, cause={})",
                    namespace, rejected.getClass().getSimpleName());
            return false;
        }
    }

    private Long requireUserId(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Cần đăng nhập để thực hiện thao tác này");
        }
        return userId;
    }
}
