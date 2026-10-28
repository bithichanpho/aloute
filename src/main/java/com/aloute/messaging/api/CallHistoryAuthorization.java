package com.aloute.messaging.api;

import java.util.Optional;

/** Server-owned authorization and metadata for persisted call history. */
public interface CallHistoryAuthorization {
    record TerminalCall(String callType, String callStatus, long durationSeconds) {}

    /** Legacy timeout guard; a missed-call record may only be created for a terminal MISSED call. */
    boolean missedFor(String callId, Long caller, Long callee);

    /** Terminal status and answer-to-end duration from the authoritative call session store. */
    default Optional<TerminalCall> terminalCallFor(String callId, Long caller, Long callee) {
        return Optional.empty();
    }
}
