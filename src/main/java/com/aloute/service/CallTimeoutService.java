package com.aloute.service;

import com.aloute.repository.IUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-owned call registry.
 *
 * <p>Production stores call state and timeout claims in Redis so any application replica can
 * validate signaling and complete a timeout after another replica restarts. Local tests may use
 * the in-process fallback by disabling {@code app.calls.distributed-state-enabled}.
 */
@Service
public class CallTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(CallTimeoutService.class);

    public static final long CALL_TIMEOUT_SECONDS = 30L;
    private static final long CALL_STATE_TTL_SECONDS = 2 * 60 * 60L;
    private static final String STATE_PREFIX = "aloute:calls:state:";
    private static final String TIMEOUTS_KEY = "aloute:calls:timeouts";
    private static final int TIMEOUT_BATCH_SIZE = 100;

    private static final DefaultRedisScript<String> REGISTER_CALL_SCRIPT =
            new DefaultRedisScript<>(
                    "local existing = redis.call('get', KEYS[1]); "
                            + "if existing then return existing; end; "
                            + "redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]); "
                            + "redis.call('zadd', KEYS[2], ARGV[3], ARGV[4]); "
                            + "return ARGV[1]",
                    String.class);

    private static final DefaultRedisScript<String> ANSWER_CALL_SCRIPT =
            new DefaultRedisScript<>(
                    "local value = redis.call('get', KEYS[1]); "
                            + "if not value then return nil; end; "
                            + "local caller, callee, kind, answered, created = "
                            + "string.match(value, '^(%d+)|(%d+)|([^|]+)|([01])|(%d+)$'); "
                            + "if not caller or caller ~= ARGV[1] or callee ~= ARGV[2] "
                            + "or answered ~= '0' then return nil; end; "
                            + "local updated = caller .. '|' .. callee .. '|' .. kind .. '|1|' .. created; "
                            + "redis.call('set', KEYS[1], updated, 'EX', ARGV[3]); "
                            + "redis.call('zrem', KEYS[2], ARGV[4]); "
                            + "return updated",
                    String.class);

    private static final DefaultRedisScript<String> FINISH_CALL_SCRIPT =
            new DefaultRedisScript<>(
                    "local value = redis.call('get', KEYS[1]); "
                            + "if not value then return nil; end; "
                            + "local caller, callee = string.match(value, '^(%d+)|(%d+)|'); "
                            + "if not caller or not ((caller == ARGV[1] and callee == ARGV[2]) "
                            + "or (caller == ARGV[2] and callee == ARGV[1])) then return nil; end; "
                            + "redis.call('del', KEYS[1]); "
                            + "redis.call('zrem', KEYS[2], ARGV[3]); "
                            + "return value",
                    String.class);

    private static final DefaultRedisScript<String> CLAIM_TIMEOUT_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('zrem', KEYS[1], ARGV[1]) == 1 then "
                            + "local value = redis.call('get', KEYS[2]); "
                            + "if value then redis.call('del', KEYS[2]); end; return value; end; "
                            + "return nil",
                    String.class);

    public record CallInfo(
            String callId,
            Long callerId,
            Long calleeId,
            String callType,
            boolean answered,
            Instant createdAt) {
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
    private final IUserRepository userRepository;
    private final NotificationService notificationService;
    private final ChatService chatService;
    private final StringRedisTemplate redis;
    private final boolean distributedStateEnabled;
    private final Map<String, LocalCall> localCalls = new ConcurrentHashMap<>();

    @Autowired
    public CallTimeoutService(
            ScheduledExecutorService callTimeoutExecutor,
            SimpMessagingTemplate messagingTemplate,
            IUserRepository userRepository,
            NotificationService notificationService,
            ChatService chatService,
            StringRedisTemplate redis,
            @Value("${app.calls.distributed-state-enabled:false}") boolean distributedStateEnabled) {
        this.scheduler = callTimeoutExecutor;
        this.messagingTemplate = messagingTemplate;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.chatService = chatService;
        this.redis = redis;
        this.distributedStateEnabled = distributedStateEnabled;
    }

    /** Compatibility constructor for isolated unit tests. */
    CallTimeoutService(
            ScheduledExecutorService callTimeoutExecutor,
            SimpMessagingTemplate messagingTemplate,
            IUserRepository userRepository,
            NotificationService notificationService,
            ChatService chatService) {
        this(callTimeoutExecutor, messagingTemplate, userRepository, notificationService,
                chatService, null, false);
    }

    /** Registers the server-side call before its offer is delivered to the target. */
    public boolean registerOffer(String callId, Long callerId, Long calleeId, String callType) {
        if (callId == null || callId.isBlank() || callId.length() > 100
                || callerId == null || calleeId == null || callerId.equals(calleeId)) return false;
        String normalizedId = callId.trim();
        String normalizedType = "audio".equalsIgnoreCase(callType) ? "audio" : "video";
        CallInfo info = new CallInfo(
                normalizedId, callerId, calleeId, normalizedType, false, Instant.now());
        return distributedStateEnabled ? registerDistributed(info) : registerLocal(info);
    }

    private boolean registerDistributed(CallInfo info) {
        String stored = redis.execute(
                REGISTER_CALL_SCRIPT,
                List.of(stateKey(info.callId()), TIMEOUTS_KEY),
                encode(info),
                String.valueOf(CALL_STATE_TTL_SECONDS),
                String.valueOf(info.createdAt().plusSeconds(CALL_TIMEOUT_SECONDS).toEpochMilli()),
                info.callId());
        return decode(info.callId(), stored)
                .map(existing -> existing.callerId().equals(info.callerId())
                        && existing.calleeId().equals(info.calleeId())
                        && existing.callType().equals(info.callType()))
                .orElse(false);
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
        if (distributedStateEnabled) {
            if (callId == null || callId.isBlank() || senderId == null || targetId == null) {
                return false;
            }
            String answered = redis.execute(
                    ANSWER_CALL_SCRIPT,
                    List.of(stateKey(callId), TIMEOUTS_KEY),
                    String.valueOf(targetId),
                    String.valueOf(senderId),
                    String.valueOf(CALL_STATE_TTL_SECONDS),
                    callId);
            return decode(callId, answered).map(CallInfo::answered).orElse(false);
        }
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
        if (distributedStateEnabled) return findDistributed(callId);
        LocalCall call = localCalls.get(callId);
        return call == null ? Optional.empty() : Optional.of(call.snapshot());
    }

    /** Ends a call only when the authenticated actor is one of its registered participants. */
    public Optional<CallInfo> finishCall(String callId, Long actorId, Long targetId) {
        if (distributedStateEnabled) {
            if (callId == null || callId.isBlank() || actorId == null || targetId == null) {
                return Optional.empty();
            }
            return decode(
                    callId,
                    redis.execute(
                            FINISH_CALL_SCRIPT,
                            List.of(stateKey(callId), TIMEOUTS_KEY),
                            String.valueOf(actorId),
                            String.valueOf(targetId),
                            callId));
        }
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

    /** Every replica may poll; the Redis script lets exactly one replica claim a timeout. */
    @Scheduled(fixedDelayString = "${app.calls.timeout-scan-ms:1000}")
    public void processDistributedTimeouts() {
        if (!distributedStateEnabled) return;
        Set<String> due = redis.opsForZSet().rangeByScore(
                TIMEOUTS_KEY, 0, Instant.now().toEpochMilli(), 0, TIMEOUT_BATCH_SIZE);
        if (due == null || due.isEmpty()) return;
        for (String callId : due) {
            String encoded = redis.execute(
                    CLAIM_TIMEOUT_SCRIPT, List.of(TIMEOUTS_KEY, stateKey(callId)), callId);
            decode(callId, encoded).filter(call -> !call.answered()).ifPresent(this::deliverMissedCall);
        }
    }

    private Optional<CallInfo> findDistributed(String callId) {
        if (callId == null || callId.isBlank()) return Optional.empty();
        return decode(callId, redis.opsForValue().get(stateKey(callId)));
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

    private static Optional<CallInfo> decode(String callId, String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 5) return Optional.empty();
            return Optional.of(new CallInfo(
                    callId, Long.valueOf(fields[0]), Long.valueOf(fields[1]), fields[2],
                    "1".equals(fields[3]), Instant.ofEpochMilli(Long.parseLong(fields[4]))));
        } catch (RuntimeException malformedState) {
            return Optional.empty();
        }
    }

    private void deliverMissedCall(CallInfo call) {
        String callTypeLabel = "audio".equalsIgnoreCase(call.callType()) ? "thoại" : "video";
        userRepository.findById(call.callerId()).ifPresent(caller ->
                userRepository.findById(call.calleeId()).ifPresent(receiver -> {
                    Map<String, Object> signal = new java.util.HashMap<>();
                    signal.put("type", "hangup");
                    signal.put("reason", "timeout");
                    signal.put("missed", true);
                    signal.put("callId", call.callId());
                    signal.put("callerUserId", call.callerId());
                    signal.put("targetUserId", call.calleeId());
                    signal.put("callType", call.callType());
                    signal.put("fromUserId", caller.getId());
                    signal.put("fromUsername", caller.getUsername());
                    signal.put("fromFullName", caller.getFullName());
                    signal.put("fromAvatarUrl", caller.getAvatarUrl());

                    messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/call", signal);
                    messagingTemplate.convertAndSendToUser(caller.getUsername(), "/queue/call", signal);
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
                }));
    }
}
