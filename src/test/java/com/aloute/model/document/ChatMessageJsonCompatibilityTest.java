package com.aloute.model.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Regression coverage for call messages already persisted in PostgreSQL JSONB. */
class ChatMessageJsonCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void deserializesAndRetainsCallDurationSeconds() throws Exception {
        String storedDocument = """
                {"id":"call-message-1","conversationId":"12_34","messageType":"CALL_END",
                 "callId":"call-1","callType":"VIDEO","callStatus":"ENDED",
                 "callDurationSeconds":87}
                """;

        ChatMessage message = mapper.readValue(storedDocument, ChatMessage.class);

        assertNotNull(message);
        assertEquals("call-message-1", message.getId());
        assertEquals(87L, message.getCallDurationSeconds());

        ChatMessage roundTripped = mapper.readValue(mapper.writeValueAsString(message), ChatMessage.class);
        assertEquals(87L, roundTripped.getCallDurationSeconds());
    }
}
