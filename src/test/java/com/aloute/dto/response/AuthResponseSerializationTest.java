package com.aloute.dto.response;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class AuthResponseSerializationTest {

    @Test
    void accessTokenIsNeverSerializedIntoJsonBody() throws Exception {
        AuthResponse response = new AuthResponse(
                "sensitive-jwt-value", 7L, "demo", "Demo User", null);

        String json = JsonMapper.builder().build().writeValueAsString(response);

        assertFalse(json.contains("accessToken"));
        assertFalse(json.contains("sensitive-jwt-value"));
        assertTrue(json.contains("demo"));
    }
}
