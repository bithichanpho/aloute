package com.aloute.service;

import com.aloute.repository.IUserRepository;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class CallTimeoutService {

    public static final long CALL_TIMEOUT_SECONDS = 30L;

    private final ScheduledExecutorService scheduler;
    private final SimpMessagingTemplate messagingTemplate;
    private final IUserRepository userRepository;
    private final NotificationService notificationService;
    private final ChatService chatService;

    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public CallTimeoutService(
            ScheduledExecutorService callTimeoutExecutor,
            SimpMessagingTemplate messagingTemplate,
            IUserRepository userRepository,
            NotificationService notificationService,
            ChatService chatService) {
        this.scheduler = callTimeoutExecutor;
        this.messagingTemplate = messagingTemplate;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.chatService = chatService;
    }

    public void schedule(String callId, Long callerId, Long receiverId, String callType) {
        if (callId == null || callId.isBlank()) return;
        cancel(callId);

        ScheduledFuture<?> future =
                scheduler.schedule(
                        () -> timeout(callId, callerId, receiverId, callType),
                        CALL_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
        pending.put(callId, future);
    }

    public void cancel(String callId) {
        if (callId == null || callId.isBlank()) return;
        ScheduledFuture<?> future = pending.remove(callId);
        if (future != null) future.cancel(false);
    }

    private void timeout(String callId, Long callerId, Long receiverId, String callType) {

        ScheduledFuture<?> marker = pending.remove(callId);
        if (marker == null) return;

        String type = "audio".equalsIgnoreCase(callType) ? "voice" : "video";
        userRepository
                .findById(callerId)
                .ifPresent(
                        caller ->
                                userRepository
                                        .findById(receiverId)
                                        .ifPresent(
                                                receiver -> {
                                                    Map<String, Object> signal =
                                                            new java.util.HashMap<>();
                                                    signal.put("type", "hangup");
                                                    signal.put("reason", "timeout");
                                                    signal.put("missed", true);
                                                    signal.put("callId", callId);
                                                    signal.put(
                                                            "callType",
                                                            callType == null ? "video" : callType);
                                                    signal.put("fromUserId", caller.getId());
                                                    signal.put(
                                                            "fromUsername", caller.getUsername());
                                                    signal.put(
                                                            "fromFullName", caller.getFullName());
                                                    signal.put(
                                                            "fromAvatarUrl", caller.getAvatarUrl());

                                                    messagingTemplate.convertAndSendToUser(
                                                            receiver.getUsername(),
                                                            "/queue/call",
                                                            signal);
                                                    messagingTemplate.convertAndSendToUser(
                                                            caller.getUsername(),
                                                            "/queue/call",
                                                            signal);

                                                    chatService.createMissedCallMessage(
                                                            callerId, receiverId, callId, callType);

                                                    notificationService.notify(
                                                            receiverId,
                                                            callerId,
                                                            "CALL_MISSED",
                                                            callId,
                                                            "đã gọi "
                                                                    + type
                                                                    + " cho bạn nhưng bạn không trả"
                                                                    + " lời trong 30 giây");
                                                }));
    }
}
