package com.aloute.service;

import com.aloute.repository.IUserRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single-instance call registry. Call identity and participants are assigned by the server;
 * a client-provided callerUserId is never treated as authoritative.
 */
@Service
public class CallTimeoutService {

    public static final long CALL_TIMEOUT_SECONDS = 30L;
    private static final long CALL_STATE_TTL_SECONDS = 2 * 60 * 60L;

    public record CallInfo(String callId, Long callerId, Long calleeId, String callType,
                           boolean answered, Instant createdAt) {
        public boolean involves(Long userId) {
            return userId != null && (userId.equals(callerId) || userId.equals(calleeId));
        }
        public boolean matchesPair(Long first, Long second) {
            return first != null && second != null
                    && ((first.equals(callerId) && second.equals(calleeId))
                        || (first.equals(calleeId) && second.equals(callerId)));
        }
    }

    private static final class ActiveCall {
        private final String callId;
        private final Long callerId;
        private final Long calleeId;
        private final String callType;
        private final Instant createdAt = Instant.now();
        private volatile boolean answered;
        private ScheduledFuture<?> timeoutFuture;
        private ScheduledFuture<?> expiryFuture;

        private ActiveCall(String callId, Long callerId, Long calleeId, String callType) {
            this.callId = callId;
            this.callerId = callerId;
            this.calleeId = calleeId;
            this.callType = callType;
        }

        private CallInfo snapshot() {
            return new CallInfo(callId, callerId, calleeId, callType, answered, createdAt);
        }
    }

    private final ScheduledExecutorService scheduler;
    private final SimpMessagingTemplate messagingTemplate;
    private final IUserRepository userRepository;
    private final NotificationService notificationService;
    private final ChatService chatService;
    private final Map<String, ActiveCall> activeCalls = new ConcurrentHashMap<>();

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

    /** Registers the server-side call before its offer is delivered to the target. */
    public synchronized boolean registerOffer(String callId, Long callerId, Long calleeId, String callType) {
        if (callId == null || callId.isBlank() || callId.length() > 100
                || callerId == null || calleeId == null || callerId.equals(calleeId)) return false;
        String normalizedType = "audio".equalsIgnoreCase(callType) ? "audio" : "video";
        ActiveCall existing = activeCalls.get(callId);
        if (existing != null) {
            return existing.callerId.equals(callerId)
                    && existing.calleeId.equals(calleeId)
                    && existing.callType.equals(normalizedType);
        }
        ActiveCall call = new ActiveCall(callId, callerId, calleeId, normalizedType);
        activeCalls.put(callId, call);
        call.timeoutFuture = scheduler.schedule(() -> timeout(call), CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        call.expiryFuture = scheduler.schedule(() -> expire(call), CALL_STATE_TTL_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    /** Only the callee may answer the active offer, and only back to the original caller. */
    public synchronized boolean confirmAnswer(String callId, Long senderId, Long targetId) {
        ActiveCall call = activeCalls.get(callId);
        if (call == null || !call.calleeId.equals(senderId) || !call.callerId.equals(targetId)) return false;
        call.answered = true;
        cancelFuture(call.timeoutFuture);
        call.timeoutFuture = null;
        return true;
    }

    /** ICE messages may travel either direction, but only between the registered participants. */
    public synchronized boolean authorizePeerSignal(String callId, Long senderId, Long targetId) {
        ActiveCall call = activeCalls.get(callId);
        return call != null && call.snapshot().matchesPair(senderId, targetId);
    }

    public synchronized Optional<CallInfo> findCall(String callId) {
        ActiveCall call = activeCalls.get(callId);
        return call == null ? Optional.empty() : Optional.of(call.snapshot());
    }

    /** Ends a call only when the authenticated actor is one of its two registered participants. */
    public synchronized Optional<CallInfo> finishCall(String callId, Long actorId, Long targetId) {
        ActiveCall call = activeCalls.get(callId);
        if (call == null || !call.snapshot().matchesPair(actorId, targetId)) return Optional.empty();
        activeCalls.remove(callId, call);
        cancelFuture(call.timeoutFuture);
        cancelFuture(call.expiryFuture);
        return Optional.of(call.snapshot());
    }

    private void timeout(ActiveCall call) {
        synchronized (this) {
            if (activeCalls.get(call.callId) != call || call.answered) return;
            activeCalls.remove(call.callId, call);
            cancelFuture(call.expiryFuture);
        }
        deliverMissedCall(call);
    }

    private synchronized void expire(ActiveCall call) {
        if (activeCalls.remove(call.callId, call)) {
            cancelFuture(call.timeoutFuture);
        }
    }

    private static void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    private void deliverMissedCall(ActiveCall call) {
        String callTypeLabel = "audio".equalsIgnoreCase(call.callType) ? "thoại" : "video";
        userRepository.findById(call.callerId).ifPresent(caller ->
                userRepository.findById(call.calleeId).ifPresent(receiver -> {
                    Map<String, Object> signal = new java.util.HashMap<>();
                    signal.put("type", "hangup");
                    signal.put("reason", "timeout");
                    signal.put("missed", true);
                    signal.put("callId", call.callId);
                    signal.put("callerUserId", call.callerId);
                    signal.put("targetUserId", call.calleeId);
                    signal.put("callType", call.callType);
                    signal.put("fromUserId", caller.getId());
                    signal.put("fromUsername", caller.getUsername());
                    signal.put("fromFullName", caller.getFullName());
                    signal.put("fromAvatarUrl", caller.getAvatarUrl());

                    messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/call", signal);
                    messagingTemplate.convertAndSendToUser(caller.getUsername(), "/queue/call", signal);
                    try {
                        chatService.createMissedCallMessage(call.callerId, call.calleeId, call.callId, call.callType);
                    } catch (RuntimeException ex) {
                        // Call signaling should still complete even if missed-call history fails.
                    }
                    try {
                        notificationService.notify(call.calleeId, call.callerId, "CALL_MISSED", call.callId,
                                "đã gọi " + callTypeLabel + " cho bạn nhưng bạn không trả lời trong 30 giây");
                    } catch (RuntimeException ex) {
                        // Notification failures are isolated from the call timeout scheduler.
                    }
                }));
    }
}
