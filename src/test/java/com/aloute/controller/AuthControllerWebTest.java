package com.aloute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aloute.common.exception.TooManyRequestsException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.config.JwtUtil;
import com.aloute.dto.response.AuthResponse;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.AuthService;
import com.aloute.security.ClientIpResolver;
import com.aloute.service.FirebaseIdentityService;
import com.aloute.service.RefreshTokenService;
import com.aloute.service.AccessTokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

class AuthControllerWebTest {
    private static final String LOGIN_JSON = "{\"username\":\" Victim \",\"password\":\"Wrong-Pass1!\"}";

    private AuthService authService;
    private JwtUtil jwtUtil;
    private AuthRateLimitService limits;
    private ClientIpResolver clientIp;
    private RefreshTokenService refreshTokens;
    private AccessTokenRevocationService accessRevocations;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        jwtUtil = mock(JwtUtil.class);
        limits = mock(AuthRateLimitService.class);
        FirebaseIdentityService firebase = mock(FirebaseIdentityService.class);
        clientIp = mock(ClientIpResolver.class);
        refreshTokens = mock(RefreshTokenService.class);
        accessRevocations = mock(AccessTokenRevocationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                authService, jwtUtil, limits, firebase, clientIp, refreshTokens,
                accessRevocations)).build();
    }

    @Test
    void logoutRevokesRefreshTokenAndExpiresHttpOnlyCookies() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge("ALOUTE_TOKEN", 0))
                .andExpect(cookie().maxAge("ALOUTE_REFRESH", 0));
        verify(refreshTokens).revoke(isNull());
    }

    @Test
    void logoutAlwaysAddsCurrentAccessTokenToDenylist() throws Exception {
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(600);
        when(jwtUtil.expiresAt("access-token")).thenReturn(expiry);

        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());

        verify(accessRevocations).revoke("access-token", expiry);
    }

    @Test
    void failedLoginsCountAgainstTheAccountEvenWhenTheIpChanges() throws Exception {
        when(authService.login(any())).thenThrow(new UnauthorizedException("Sai tên đăng nhập hoặc mật khẩu"));

        when(clientIp.resolve(any())).thenReturn("203.0.113.10");
        performLoginIgnoringErrors();
        when(clientIp.resolve(any())).thenReturn("198.51.100.77");
        performLoginIgnoringErrors();

        // One IP-independent counter keyed by the normalized identifier, shared by both IPs.
        verify(limits, times(2)).checkAndRecord(
                eq("LOGIN_ACCOUNT_GLOBAL"), eq("victim"), eq(30),
                eq(Duration.ofMinutes(15)), eq(Duration.ofMinutes(15)), anyString(), anyString());
    }

    @Test
    void blockedAccountIsRejectedBeforeThePasswordIsChecked() throws Exception {
        when(clientIp.resolve(any())).thenReturn("203.0.113.10");
        doThrow(new TooManyRequestsException("blocked"))
                .when(limits).rejectIfBlocked(eq("LOGIN_ACCOUNT_GLOBAL"), eq("victim"), anyString());

        performLoginIgnoringErrors();

        verifyNoInteractions(authService);
    }

    @Test
    void successfulLoginClearsTheAccountFailureCounter() throws Exception {
        when(clientIp.resolve(any())).thenReturn("203.0.113.10");
        when(authService.login(any()))
                .thenReturn(new AuthResponse("access-token", 1L, "victim", "Victim", null));
        when(refreshTokens.issue(1L)).thenReturn("refresh-token");
        when(refreshTokens.getLifetimeSeconds()).thenReturn(3600L);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isOk());

        verify(limits).reset("LOGIN_ACCOUNT_GLOBAL", "victim");
        verify(limits, never()).checkAndRecord(
                eq("LOGIN_ACCOUNT_GLOBAL"), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(Duration.class), any(Duration.class), anyString(), anyString());
    }

    @Test
    void emailVerificationAcceptsEmailAndSixDigitOtp() throws Exception {
        when(clientIp.resolve(any())).thenReturn("203.0.113.10");

        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"User@Example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).verifyEmail("user@example.com", "123456");
        verify(limits).reset("VERIFY_OTP_TARGET", "user@example.com");
    }

    @Test
    void emailVerificationRejectsSevenDigitOtp() throws Exception {
        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"User@Example.com\",\"code\":\"1234567\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void oldVerificationLinkPayloadIsRejected() throws Exception {
        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"old-link-token\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    void passwordResetAcceptsEmailOtpAndStrongPassword() throws Exception {
        when(clientIp.resolve(any())).thenReturn("203.0.113.10");

        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"User@Example.com\",\"code\":\"123456\","
                                + "\"newPassword\":\"New-Pass1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).resetPassword("user@example.com", "123456", "New-Pass1!");
        verify(limits).reset("RESET_OTP_TARGET", "user@example.com");
    }

    private void performLoginIgnoringErrors() {
        try {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON));
        } catch (Exception expectedBecauseNoControllerAdviceIsRegistered) {
            // The exception handler is not part of this standalone slice; interactions are what we assert.
        }
    }
}
