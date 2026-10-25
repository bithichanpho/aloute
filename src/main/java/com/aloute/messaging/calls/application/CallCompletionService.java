package com.aloute.messaging.calls.application;

import com.aloute.messaging.api.MissedCallHistory;
import com.aloute.messaging.calls.domain.CallSessions;
import com.aloute.notifications.api.NotificationIntents;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One PostgreSQL commit: history, existing notification outbox, completion marker. No network I/O. */
@Service
public class CallCompletionService {
    private final CallSessions sessions;
    private final MissedCallHistory history;
    private final NotificationIntents notifications;

    public CallCompletionService(CallSessions sessions, MissedCallHistory history, NotificationIntents notifications) {
        this.sessions = sessions;
        this.history = history;
        this.notifications = notifications;
    }

    @Transactional
    public Optional<CallSessions.Snapshot> complete(CallSessions.Claim claim) {
        var locked = sessions.lockClaim(claim);
        if (locked.isEmpty()) return Optional.empty();
        var call = locked.get();
        history.createMissedCallMessage(call.callerId(), call.calleeId(), call.callId(), call.callType());
        String type = "audio".equals(call.callType()) ? "thoại" : "video";
        notifications.enqueue(call.calleeId(), call.callerId(), "CALL_MISSED", call.callId(),
                "đã gọi " + type + " cho bạn nhưng bạn không trả lời trong 30 giây");
        sessions.completed(claim);
        return locked;
    }
}
