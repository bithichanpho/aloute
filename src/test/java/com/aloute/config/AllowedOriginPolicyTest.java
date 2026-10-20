package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AllowedOriginPolicyTest {
    @Test
    void parsesDistinctCredentialedOrigins() {
        assertEquals(
                List.of("https://aloute.example", "https://admin.aloute.example"),
                AllowedOriginPolicy.parse(
                        " https://aloute.example,https://admin.aloute.example,https://aloute.example "));
    }

    @Test
    void refusesUniversalWildcardWithCredentialCookies() {
        assertThrows(IllegalStateException.class, () -> AllowedOriginPolicy.parse("*"));
    }

    @Test
    void refusesEmptyOriginConfiguration() {
        assertThrows(IllegalStateException.class, () -> AllowedOriginPolicy.parse(" , "));
    }
}
