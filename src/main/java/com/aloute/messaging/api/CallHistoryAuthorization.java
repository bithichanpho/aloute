package com.aloute.messaging.api;

/** History may only be created for the participants of an authoritative terminal MISSED call. */
public interface CallHistoryAuthorization {
    boolean missedFor(String callId, Long caller, Long callee);
}
