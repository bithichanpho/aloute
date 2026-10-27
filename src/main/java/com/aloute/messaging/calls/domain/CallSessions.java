package com.aloute.messaging.calls.domain;

import com.aloute.messaging.api.CallSignaling;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Calls owns its durable state/processing journal. No Redis/PostgreSQL distributed transaction. */
public interface CallSessions {
    record Snapshot(String callId, Long callerId, Long calleeId, String callType, boolean answered,
            Instant createdAt, String state) implements CallSignaling.Session {}
    /** Server-authoritative history for a terminal call; duration is answer-to-end, never ring time. */
    record History(String callType, String state, long durationSeconds) {}
    record Claim(Snapshot call, String token) {}

    boolean register(String callId, Long caller, Long callee, String type);
    boolean answer(String callId, Long sender, Long target);
    Optional<Snapshot> active(String callId);
    Optional<Snapshot> finish(String callId, Long actor, Long target);
    Optional<History> terminalHistory(String callId, Long caller, Long callee);
    boolean missedFor(String callId, Long caller, Long callee);
    void expireDue();
    List<Claim> claimDue();
    Optional<Snapshot> lockClaim(Claim claim);
    void completed(Claim claim);
    void failed(Claim claim, String reason);
}
