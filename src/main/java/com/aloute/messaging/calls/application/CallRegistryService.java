package com.aloute.messaging.calls.application;

import com.aloute.notifications.api.Notifications;

import com.aloute.users.api.CallParticipants;
import com.aloute.messaging.api.CallSignaling;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-owned call registry.
 *
 * <p>Production uses PostgreSQL state and recoverable leases. Redis is a best-effort mirror.
 * The seven-argument construction adapter is retained only for isolated local compatibility tests;
 * Spring always requires the durable store and transactional completion provider.
 */
@Service
@org.springframework.context.annotation.Primary
public class CallRegistryService implements CallSignaling {

    private static final Logger log = LoggerFactory.getLogger(CallRegistryService.class);

    public static final long CALL_TIMEOUT_SECONDS = 30L;
    private static final long CALL_STATE_TTL_SECONDS = 2 * 60 * 60L;
    private static final String STATE_PREFIX = "aloute:calls:state:";
    public record CallInfo(
            String callId,
            Long callerId,
            Long calleeId,
            String callType,
            boolean answered,
            Instant createdAt, String state) implements CallSignaling.Session {
        public CallInfo(String callId, Long callerId, Long calleeId, String callType, boolean answered, Instant createdAt) {
            this(callId, callerId, calleeId, callType, answered, createdAt, answered ? "ACTIVE" : "RINGING");
        }
        public boolean involves(Long userId) {
            return userId != null && (userId.equals(callerId) || userId.equals(calleeId));
        }

        public boolean matchesPair(Long first, Long second) {
            return first != null
                    && second != null
                    && ((first.equals(callerId) && second.equals(calleeId))
                            || (first.equals(calleeId) && second.equals(callerId)));
        }

    }

    private static final class LocalCall {
        private final CallInfo info;
        private volatile boolean answered;
        private ScheduledFuture<?> timeoutFuture;
        private ScheduledFuture<?> expiryFuture;

        private LocalCall(CallInfo info) {
            this.info = info;
        }

        private CallInfo snapshot() {
            return new CallInfo(
                    info.callId(), info.callerId(), info.calleeId(), info.callType(), answered,
                    info.createdAt());
        }
    }

    private final ScheduledExecutorService scheduler;
    private final SimpMessagingTemplate messagingTemplate;
    private final CallParticipants participants;
    private final Notifications notificationService;
    private final com.aloute.messaging.api.MissedCallHistory chatService;
    private final StringRedisTemplate redis;
    private final boolean distributedStateEnabled;
    private final com.aloute.messaging.calls.domain.CallSessions durable;
    private final CallCompletionService completion;
    private final Map<String, LocalCall> localCalls = new ConcurrentHashMap<>();

    public CallRegistryService(
            ScheduledExecutorService callTimeoutExecutor,
            SimpMessagingTemplate messagingTemplate,
            CallParticipants participants,
            Notifications notificationService,
            com.aloute.messaging.api.MissedCallHistory chatService,
            StringRedisTemplate redis,
            @Value("${app.calls.distributed-state-enabled:false}") boolean distributedStateEnabled) {
        this(callTimeoutExecutor, messagingTemplate, participants, notificationService, chatService,
                redis, distributedStateEnabled, null, null);
    }

    @Autowired
    public CallRegistryService(ScheduledExecutorService callTimeoutExecutor, SimpMessagingTemplate messagingTemplate,
            CallParticipants participants, Notifications notificationService,
            com.aloute.messaging.api.MissedCallHistory chatService, StringRedisTemplate redis,
            @Value("${app.calls.distributed-state-enabled:false}") boolean distributedStateEnabled,
            com.aloute.messaging.calls.domain.CallSessions durable, CallCompletionService completion) {
        if (distributedStateEnabled && durable == null)
            throw new IllegalArgumentException("Distributed calls require PostgreSQL durable sessions");
        this.durable = durable;
        this.completion = completion;
        this.scheduler = callTimeoutExecutor;
        this.messagingTemplate = messagingTemplate;
        this.participants = participants;
        this.notificationService = notificationService;
        this.chatService = chatService;
        this.redis = redis;
        this.distributedStateEnabled = distributedStateEnabled;
    }

    /** Registers the server-side call before its offer is delivered to the target. */
    public boolean registerOffer(String callId, Long callerId, Long calleeId, String callType) {
        if (callId == null || callId.isBlank() || callId.length() > 100
                || callerId == null || calleeId == null || callerId <= 0 || calleeId <= 0 || callerId.equals(calleeId)) return false;
        String normalizedId = callId.trim();
        String normalizedType = "audio".equalsIgnoreCase(callType) ? "audio" : "video";
        CallInfo info = new CallInfo(
                normalizedId, callerId, calleeId, normalizedType, false, Instant.now());
        if (durable != null) {
            boolean registered = durable.register(normalizedId, callerId, calleeId, normalizedType);
            if (registered) mirror(info);
            return registered;
        }
        return registerLocal(info);
    }

    private synchronized boolean registerLocal(CallInfo info) {
        LocalCall existing = localCalls.get(info.callId());
        if (existing != null) {
            CallInfo current = existing.snapshot();
            return current.callerId().equals(info.callerId())
                    && current.calleeId().equals(info.calleeId())
                    && current.callType().equals(info.callType());
        }
        LocalCall call = new LocalCall(info);
        localCalls.put(info.callId(), call);
        call.timeoutFuture = scheduler.schedule(
                () -> timeoutLocal(call), CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        call.expiryFuture = scheduler.schedule(
                () -> expireLocal(call), CALL_STATE_TTL_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    /** Only the callee may answer the active offer, and only back to the original caller. */
    public boolean confirmAnswer(String callId, Long senderId, Long targetId) {
        if (durable != null) return validPair(callId, senderId, targetId)
                && durable.answer(callId, senderId, targetId);
        synchronized (this) {
            LocalCall call = localCalls.get(callId);
            if (call == null || !call.info.calleeId().equals(senderId)
                    || !call.info.callerId().equals(targetId)) return false;
            call.answered = true;
            cancelFuture(call.timeoutFuture);
            call.timeoutFuture = null;
            return true;
        }
    }

    /** ICE messages may travel either direction, but only between registered participants. */
    public boolean authorizePeerSignal(String callId, Long senderId, Long targetId) {
        return findCall(callId).map(call -> call.matchesPair(senderId, targetId)).orElse(false);
    }

    public Optional<CallInfo> findCall(String callId) {
        if (callId == null || callId.isBlank()) return Optional.empty();
        if (durable != null) return durable.active(callId).map(CallRegistryService::view);
        LocalCall call = localCalls.get(callId);
        return call == null ? Optional.empty() : Optional.of(call.snapshot());
    }

    /** Ends a call only when the authenticated actor is one of its registered participants. */
    public Optional<CallInfo> finishCall(String callId, Long actorId, Long targetId) {
        if (durable != null) return validPair(callId, actorId, targetId)
                ? durable.finish(callId, actorId, targetId).map(CallRegistryService::view) : Optional.empty();
        synchronized (this) {
            LocalCall removed = localCalls.get(callId);
            if (removed == null || !removed.snapshot().matchesPair(actorId, targetId)) {
                return Optional.empty();
            }
            localCalls.remove(callId);
            cancelFuture(removed.timeoutFuture);
            cancelFuture(removed.expiryFuture);
            return Optional.of(removed.snapshot());
        }
    }

    /** Every replica may poll; PostgreSQL row leases serialize recovery across instances. */
    public void processDistributedTimeouts() {
        if (durable != null) {
            durable.expireDue();
            for (var claim : durable.claimDue()) {
                try {
                    // Returns only after PostgreSQL history + notification outbox + completion commit.
                    completion.complete(claim).ifPresent(call -> deliverTimeoutSignal(view(call)));
                } catch (RuntimeException failure) {
                    log.warn("Call recovery failed (callId={}, cause={})", claim.call().callId(),
                            failure.getClass().getSimpleName());
                    try { durable.failed(claim, failure.getClass().getSimpleName()); }
                    catch (RuntimeException unavailable) {
                        // The persisted lease expires and another process can recover after DB returns.
                        log.warn("Call recovery retry update unavailable (callId={})", claim.call().callId());
                    }
                }
            }
            return;
        }
    }

    private synchronized void timeoutLocal(LocalCall call) {
        if (localCalls.get(call.info.callId()) != call || call.answered) return;
        localCalls.remove(call.info.callId(), call);
        cancelFuture(call.expiryFuture);
        deliverMissedCall(call.snapshot());
    }

    private synchronized void expireLocal(LocalCall call) {
        if (localCalls.remove(call.info.callId(), call)) cancelFuture(call.timeoutFuture);
    }

    private static void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    private static String stateKey(String callId) {
        return STATE_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(callId.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(CallInfo call) {
        return call.callerId() + "|" + call.calleeId() + "|" + call.callType() + "|"
                + (call.answered() ? "1" : "0") + "|" + call.createdAt().toEpochMilli();
    }

    private void deliverTimeoutSignal(CallInfo call) {
        var found = participants.lookup(List.of(call.callerId(), call.calleeId()));
        var caller = found.get(call.callerId());
        var receiver = found.get(call.calleeId());
        if (caller == null || receiver == null) return;
        Map<String, Object> signal = new java.util.HashMap<>();
        signal.put("type", "hangup");
        signal.put("reason", "timeout");
        signal.put("missed", true);
        signal.put("callId", call.callId());
        signal.put("callerUserId", call.callerId());
        signal.put("targetUserId", call.calleeId());
        signal.put("callType", call.callType());
        signal.put("fromUserId", caller.id());
        signal.put("fromUsername", caller.username());
        signal.put("fromFullName", caller.fullName());
        signal.put("fromAvatarUrl", caller.avatarUrl());

        sendTimeoutSignal(receiver.username(), signal, call.callId());
        sendTimeoutSignal(caller.username(), signal, call.callId());
    }

    private void deliverMissedCall(CallInfo call) {
        deliverTimeoutSignal(call);
        String callTypeLabel = "audio".equalsIgnoreCase(call.callType()) ? "thoại" : "video";
        try {
            chatService.createMissedCallMessage(
                    call.callerId(), call.calleeId(), call.callId(), call.callType());
        } catch (RuntimeException historyFailure) {
            // Signaling remains available if history persistence fails, but keep the
            // failure observable so operations can repair missing call history.
            log.warn(
                    "Missed-call history persistence failed (callId={}, callerId={}, calleeId={}, cause={})",
                    call.callId(), call.callerId(), call.calleeId(),
                    historyFailure.getClass().getSimpleName(), historyFailure);
        }
        try {
            notificationService.notify(
                    call.calleeId(), call.callerId(), "CALL_MISSED", call.callId(),
                    "đã gọi " + callTypeLabel
                            + " cho bạn nhưng bạn không trả lời trong 30 giây");
        } catch (RuntimeException notificationFailure) {
            // Notification failure must not retry the timeout claim, but it must remain
            // visible because otherwise a durable outbox outage is silently lost.
            log.warn(
                    "Missed-call notification enqueue failed (callId={}, callerId={}, calleeId={}, cause={})",
                    call.callId(), call.callerId(), call.calleeId(),
                    notificationFailure.getClass().getSimpleName(), notificationFailure);
        }
    }

    private static CallInfo view(com.aloute.messaging.calls.domain.CallSessions.Snapshot call) {
        return new CallInfo(call.callId(), call.callerId(), call.calleeId(), call.callType(),
                call.answered(), call.createdAt(), call.state());
    }

    private boolean validPair(String id, Long first, Long second) {
        return id != null && !id.isBlank() && id.length() <= 100 && first != null && second != null
                && first > 0 && second > 0 && !first.equals(second);
    }

    /** Redis remains an optional acceleration/mirroring resource, never the durable journal. */
    private void mirror(CallInfo call) {
        if (!distributedStateEnabled || redis == null) return;
        try {
            redis.opsForValue().set(stateKey(call.callId()), encode(call),
                    java.time.Duration.ofSeconds(CALL_STATE_TTL_SECONDS));
        } catch (RuntimeException failure) {
            log.warn("Call Redis mirror unavailable (callId={}, cause={})", call.callId(),
                    failure.getClass().getSimpleName());
        }
    }

    private void sendTimeoutSignal(String username, Map<String, Object> signal, String callId) {
        try {
            messagingTemplate.convertAndSendToUser(username, "/queue/call", signal);
        } catch (RuntimeException signalFailure) {
            log.warn("Call timeout signal failed (callId={}, cause={})", callId,
                    signalFailure.getClass().getSimpleName());
        }
    }
}
