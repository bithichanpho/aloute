package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

class JwtHandshakeInterceptorTest {
    @Test
    void acceptsValidAccessCookieAndStoresServerValidatedIdentityAndExpiry() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.extractUsername("valid-token")).thenReturn("alice");
        when(jwt.isTokenValid("valid-token", "alice")).thenReturn(true);
        Instant issued = Instant.parse("2026-10-17T00:00:00Z");
        Instant expires = issued.plusSeconds(3600);
        when(jwt.issuedAt("valid-token")).thenReturn(issued);
        when(jwt.expiresAt("valid-token")).thenReturn(expires);

        JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(jwt);
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws");
        servlet.setCookies(new Cookie(JwtAuthFilter.COOKIE_NAME, "valid-token"));
        HashMap<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertTrue(accepted);
        assertEquals("alice", attributes.get(JwtHandshakeInterceptor.ATTR_USERNAME));
        assertEquals(issued, attributes.get("tokenIssuedAt"));
        assertEquals(expires, attributes.get("tokenExpiresAt"));
    }

    @Test
    void marksMissingOrExpiredAccessCookieSoStompErrorTriggersRefreshAndReconnect() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.extractUsername("expired-token")).thenReturn("alice");
        when(jwt.isTokenValid("expired-token", "alice")).thenReturn(false);
        JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(jwt);

        MockHttpServletRequest noCookie = new MockHttpServletRequest("GET", "/ws");
        HashMap<String, Object> missingAttributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(
                new ServletServerHttpRequest(noCookie), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), missingAttributes));
        assertTrue(String.valueOf(missingAttributes.get(JwtHandshakeInterceptor.ATTR_AUTH_FAILURE))
                .contains("Access token"));

        MockHttpServletRequest expired = new MockHttpServletRequest("GET", "/ws");
        expired.setCookies(new Cookie(JwtAuthFilter.COOKIE_NAME, "expired-token"));
        HashMap<String, Object> expiredAttributes = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(
                new ServletServerHttpRequest(expired), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), expiredAttributes));
        assertTrue(String.valueOf(expiredAttributes.get(JwtHandshakeInterceptor.ATTR_AUTH_FAILURE))
                .contains("expired"));
    }
}
