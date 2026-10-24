package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class AuthTokenServiceTest {

    @Test
    void generatedOtpAlwaysContainsExactlySixDigits() {
        AuthTokenService service = new AuthTokenService();

        for (int i = 0; i < 100; i++) {
            assertTrue(service.generateOtp().matches("\\d{6}"));
        }
    }

    @Test
    void otpHashIsBoundToTheEmailAndComparedInConstantTime() {
        AuthTokenService service = new AuthTokenService();
        String stored = service.hashOtp("a@example.com", "123456");

        assertTrue(service.otpMatches("A@Example.com", "123456", stored));
        assertFalse(service.otpMatches("a@example.com", "654321", stored));
        assertFalse(service.otpMatches("b@example.com", "123456", stored));
        assertNotEquals(stored, service.hashOtp("b@example.com", "123456"));
    }
}
