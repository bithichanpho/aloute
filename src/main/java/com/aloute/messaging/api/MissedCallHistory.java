package com.aloute.messaging.api;

import com.aloute.dto.response.ChatMessageResponse;

/** Calls requests history through the messaging owner, never its message repository. */
public interface MissedCallHistory {
    ChatMessageResponse createMissedCallMessage(Long callerId, Long receiverId, String callId, String callType);
}
