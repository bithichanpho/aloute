package com.aloute.service;

import com.aloute.messaging.api.CallSignaling;
import com.aloute.messaging.api.MissedCallHistory;
import com.aloute.messaging.calls.application.CallRegistryService;
import com.aloute.notifications.api.Notifications;
import com.aloute.repository.IUserRepository;
import com.aloute.users.infrastructure.JpaCallParticipants;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Legacy Java/scheduling facade. The state machine is owned by messaging.calls. */
@Service
public class CallTimeoutService implements CallSignaling {
    public static final long CALL_TIMEOUT_SECONDS = CallRegistryService.CALL_TIMEOUT_SECONDS;
    private final CallRegistryService calls;

    public record CallInfo(String callId, Long callerId, Long calleeId, String callType,
            boolean answered, Instant createdAt) implements CallSignaling.Session {
        public boolean involves(Long userId) {
            return userId != null && (userId.equals(callerId) || userId.equals(calleeId));
        }
        public boolean matchesPair(Long first, Long second) {
            return first != null && second != null
                    && ((first.equals(callerId) && second.equals(calleeId))
                            || (first.equals(calleeId) && second.equals(callerId)));
        }
    }

    @Autowired
    public CallTimeoutService(CallRegistryService calls) { this.calls = calls; }

    /** Compatibility for existing direct constructors; production injects the module service. */
    public CallTimeoutService(ScheduledExecutorService scheduler, SimpMessagingTemplate messaging,
            IUserRepository users, Notifications notifications, MissedCallHistory history,
            StringRedisTemplate redis, boolean distributedStateEnabled) {
        this(new CallRegistryService(scheduler, messaging, new JpaCallParticipants(users),
                notifications, history, redis, distributedStateEnabled));
    }

    CallTimeoutService(ScheduledExecutorService scheduler, SimpMessagingTemplate messaging,
            IUserRepository users, Notifications notifications, MissedCallHistory history) {
        this(scheduler, messaging, users, notifications, history, null, false);
    }

    public boolean registerOffer(String callId, Long callerId, Long calleeId, String callType) {
        return calls.registerOffer(callId, callerId, calleeId, callType);
    }
    public boolean confirmAnswer(String callId, Long senderId, Long targetId) {
        return calls.confirmAnswer(callId, senderId, targetId);
    }
    public boolean authorizePeerSignal(String callId, Long senderId, Long targetId) {
        return calls.authorizePeerSignal(callId, senderId, targetId);
    }
    public Optional<CallInfo> findCall(String callId) { return calls.findCall(callId).map(CallTimeoutService::view); }
    public Optional<CallInfo> finishCall(String callId, Long actorId, Long targetId) {
        return calls.finishCall(callId, actorId, targetId).map(CallTimeoutService::view);
    }
    @Scheduled(fixedDelayString = "${app.calls.timeout-scan-ms:1000}")
    public void processDistributedTimeouts() { calls.processDistributedTimeouts(); }

    private static CallInfo view(CallSignaling.Session call) {
        return new CallInfo(call.callId(), call.callerId(), call.calleeId(), call.callType(),
                call.answered(), call.createdAt());
    }
}
