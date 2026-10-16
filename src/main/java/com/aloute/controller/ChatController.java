package com.aloute.controller;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.request.SendMessageRequest;
import com.aloute.dto.response.ChatMessageResponse;
import com.aloute.dto.response.ConversationResponse;
import com.aloute.repository.IUserRepository;
import com.aloute.security.SecurityUtils;
import com.aloute.service.ChatService;
import com.aloute.service.FriendService;
import com.aloute.service.PresenceService;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatController {
    public record TypingSignal(@Positive Long receiverId) {}
    public record DeliverySignal(@NotBlank @Size(max = 100) String messageId) {}

    public record CallSignal(
            @Positive Long targetUserId,
            @Positive Long callerUserId,
            @Size(max = 100) String callId,
            @Pattern(regexp = "audio|video") String callType,
            Boolean missed,
            Object offer,
            Object answer,
            Object candidate) {}
    private final com.aloute.service.ContentAccessService access;

    private final ChatService chatService;
    private final IUserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceService presenceService;
    private final FriendService friendService;
    private final com.aloute.service.NotificationService notificationService;
    private final com.aloute.service.CallTimeoutService callTimeoutService;
    private final Validator validator;
    private final com.aloute.service.AuthRateLimitService rateLimitService;

    public ChatController(
            ChatService chatService,
            IUserRepository userRepository,
            SimpMessagingTemplate messagingTemplate,
            PresenceService presenceService,
            FriendService friendService,
            com.aloute.service.NotificationService notificationService,
            com.aloute.service.CallTimeoutService callTimeoutService,
            com.aloute.service.ContentAccessService access,
            Validator validator,
            com.aloute.service.AuthRateLimitService rateLimitService) {
        this.access = access;

        this.chatService = chatService;
        this.userRepository = userRepository;
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

    @GetMapping("/messages/{otherUserId}")
    public ApiResponse<List<ChatMessageResponse>> getMessages(
            Authentication authentication,
            @PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long otherUserId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") int page) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getMessages(userId, otherUserId, page));
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
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") @Min(value = 0, message = "Trang phải từ 0 trở lên") int page) {
        Long userId = requireUserId(authentication);
        return ApiResponse.success(chatService.getGroupMessages(groupId, userId, page));
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
    public ApiResponse<Boolean> isOnline(@PathVariable @Positive(message = "Mã người dùng không hợp lệ") Long userId) {
        return ApiResponse.success(presenceService.isOnline(userId));
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Valid SendMessageRequest request, Principal principal) {
        if (principal == null
                || principal.getName() == null
                || principal.getName().startsWith("anonymous-")) {
            return;
        }

        userRepository
                .findByUsername(principal.getName())
                .ifPresent(
                        sender -> {
                            try {
                                var violations = validator.validate(request);
                                if (!violations.isEmpty()) {
                                    throw new com.aloute.common.exception.BadRequestException(
                                            violations.iterator().next().getMessage());
                                }
                                rateLimitService.checkAndRecord(
                                        "CHAT_MESSAGE", sender.getId().toString(), 120,
                                        java.time.Duration.ofMinutes(5),
                                        java.time.Duration.ofMinutes(5),
                                        "Bạn gửi tin quá nhanh. Thử lại sau %d phút.",
                                        "Tạm khóa gửi tin do gửi quá nhiều yêu cầu.");
                                if (request.getGroupId() != null) {
                                    chatService.sendGroupMessage(
                                            sender.getId(),
                                            request.getGroupId(),
                                            request.getContent(),
                                            request.getClientId(),
                                            request.getMediaUrl(),
                                            request.getMediaType(),
                                            request.getMediaName(),
                                            request.getMediaSize());
                                } else {
                                    chatService.sendMessage(
                                            sender.getId(),
                                            request.getReceiverId(),
                                            request.getContent(),
                                            request.getClientId(),
                                            request.getMediaUrl(),
                                            request.getMediaType(),
                                            request.getMediaName(),
                                            request.getMediaSize());
                                }
                            } catch (RuntimeException ex) {

                                messagingTemplate.convertAndSendToUser(
                                        principal.getName(),
                                        "/queue/errors",
                                        Map.of(
                                                "message",
                                                ex.getMessage(),
                                                "clientId",
                                                request.getClientId() != null
                                                        ? request.getClientId()
                                                        : ""));
                            }
                        });
    }

    @MessageMapping("/chat.delivered")
    public void acknowledgeDelivered(@Valid DeliverySignal payload, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().startsWith("anonymous-")) return;
        if (!validator.validate(payload).isEmpty()) return;
        userRepository.findByUsername(principal.getName()).ifPresent(user -> {
            try {
                if (user.isActive()) chatService.markAsDelivered(user.getId(), payload.messageId());
            } catch (RuntimeException ex) {
                // A duplicate ACK or an already-deleted message must not interrupt the chat socket.
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
        userRepository
                .findByUsername(principal.getName())
                .ifPresent(
                        sender -> {
                            try {
                                Long receiverId = payload.receiverId();
                                if (!sender.isActive()
                                        || !access.profileVisible(sender.getId(), receiverId))
                                    return;
                                userRepository
                                        .findById(receiverId)
                                        .ifPresent(
                                                receiver ->
                                                        messagingTemplate.convertAndSendToUser(
                                                                receiver.getUsername(),
                                                                "/queue/typing",
                                                                Map.of(
                                                                        "senderId",
                                                                        sender.getId())));
                            } catch (RuntimeException ignored) {

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

        userRepository
                .findByUsername(principal.getName())
                .ifPresent(
                        sender -> {
                            if (sender.getId().equals(targetId)
                                    || !sender.isActive()
                                    || !access.profileVisible(sender.getId(), targetId)) return;
                            if (!friendService.isFriend(sender.getId(), targetId)) return;

                            userRepository
                                    .findById(targetId)
                                    .ifPresent(
                                            target -> {
                                                Map<String, Object> signal = new java.util.HashMap<>();
                                                if (payload.offer() != null) signal.put("offer", payload.offer());
                                                if (payload.answer() != null) signal.put("answer", payload.answer());
                                                if (payload.candidate() != null) signal.put("candidate", payload.candidate());
                                                signal.put("targetUserId", payload.targetUserId());
                                                if (payload.callerUserId() != null) signal.put("callerUserId", payload.callerUserId());
                                                if (payload.callId() != null) signal.put("callId", payload.callId());
                                                if (payload.missed() != null) signal.put("missed", payload.missed());
                                                signal.put("type", type);
                                                signal.put("fromUserId", sender.getId());
                                                signal.put("fromUsername", sender.getUsername());
                                                signal.put("fromFullName", sender.getFullName());
                                                signal.put("fromAvatarUrl", sender.getAvatarUrl());
                                                signal.put(
                                                        "callType",
                                                        payload.callType() == null ? "video" : payload.callType());
                                                messagingTemplate.convertAndSendToUser(
                                                        target.getUsername(),
                                                        "/queue/call",
                                                        signal);

                                                String callId =
                                                        String.valueOf(
                                                                payload.callId() == null ? "" : payload.callId());
                                                String callType =
                                                        String.valueOf(
                                                                payload.callType() == null
                                                                        ? "video"
                                                                        : payload.callType());
                                                if ("offer".equals(type) && !callId.isBlank()) {

                                                    callTimeoutService.schedule(
                                                            callId,
                                                            sender.getId(),
                                                            targetId,
                                                            callType);
                                                } else if ("answer".equals(type)) {
                                                    callTimeoutService.cancel(callId);
                                                } else if ("hangup".equals(type)) {
                                                    callTimeoutService.cancel(callId);
                                                    boolean missed =
                                                            Boolean.TRUE.equals(payload.missed());
                                                    Long callerId = payload.callerUserId();

                                                    if (missed
                                                            && callerId != null
                                                            && sender.getId().equals(callerId)) {
                                                        notificationService.notify(
                                                                targetId,
                                                                sender.getId(),
                                                                "CALL_MISSED",
                                                                callId,
                                                                "đã gọi "
                                                                        + ("audio"
                                                                                        .equalsIgnoreCase(
                                                                                                callType)
                                                                                ? "thoại"
                                                                                : "video")
                                                                        + " cho bạn");
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
