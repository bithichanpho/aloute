package com.aloute.messaging.calls.application;

import com.aloute.messaging.api.CallHistoryAuthorization;
import com.aloute.messaging.calls.domain.CallSessions;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DurableCallHistoryAuthorization implements CallHistoryAuthorization {
    private final CallSessions sessions;

    public DurableCallHistoryAuthorization(CallSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean missedFor(String id, Long caller, Long callee) {
        return sessions.missedFor(id, caller, callee);
    }

    @Override
    public Optional<TerminalCall> terminalCallFor(String id, Long caller, Long callee) {
        return sessions.terminalHistory(id, caller, callee)
                .map(history -> new TerminalCall(history.callType(), history.state(), history.durationSeconds()));
    }
}
