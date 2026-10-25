package com.aloute.messaging.calls.application;

import com.aloute.messaging.api.CallHistoryAuthorization;
import com.aloute.messaging.calls.domain.CallSessions;
import org.springframework.stereotype.Service;

@Service
public class DurableCallHistoryAuthorization implements CallHistoryAuthorization {
    private final CallSessions sessions;
    public DurableCallHistoryAuthorization(CallSessions sessions) { this.sessions = sessions; }
    public boolean missedFor(String id, Long caller, Long callee) { return sessions.missedFor(id, caller, callee); }
}
