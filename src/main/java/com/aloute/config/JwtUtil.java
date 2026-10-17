package com.aloute.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import javax.crypto.SecretKey;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private com.aloute.service.AccessTokenRevocationService accessRevocations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAccessRevocations(com.aloute.service.AccessTokenRevocationService service) {
        this.accessRevocations = service;
    }
    private final long expirationMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret, @Value("${jwt.expiration}") long expirationMs) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must contain at least 32 UTF-8 bytes; generate a random key before starting ALOUTE");
        }
        if (expirationMs < 60_000L || expirationMs > 86_400_000L) {
            throw new IllegalStateException("JWT_EXPIRATION must be between 60,000 ms and 86,400,000 ms");
        }
        this.secretKey = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username, Long userId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public java.time.Instant issuedAt(String token) {
        return extractClaim(token, Claims::getIssuedAt).toInstant();
    }

    public java.time.Instant expiresAt(String token) {
        return extractClaim(token, Claims::getExpiration).toInstant();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Long extractUserId(String token) {
        return extractAllClaims(token).get("userId", Long.class);
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public boolean isTokenValid(String token, String username) {
        try {
            String extracted = extractUsername(token);
            return extracted.equals(username) && !isTokenExpired(token)
                    && (accessRevocations == null || !accessRevocations.isRevoked(token));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }
}
