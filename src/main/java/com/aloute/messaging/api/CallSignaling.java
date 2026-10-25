package com.aloute.messaging.api;

import java.time.Instant;
import java.util.Optional;

/** Server-owned call authorization/state, independent of REST/STOMP payloads and storage. */
public interface CallSignaling {
    interface Session {
        String callId();
        Long callerId();
        Long calleeId();
        String callType();
        boolean answered();
        Instant createdAt();
        default String state() { return answered() ? "ACTIVE" : "RINGING"; }
    }

    boolean registerOffer(String callId, Long callerId, Long calleeId, String callType);
    boolean confirmAnswer(String callId, Long senderId, Long targetId);
    boolean authorizePeerSignal(String callId, Long senderId, Long targetId);
    Optional<? extends Session> findCall(String callId);
    Optional<? extends Session> finishCall(String callId, Long actorId, Long targetId);
}
