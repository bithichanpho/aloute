package com.aloute.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM encryption for TOTP secrets, using a key independent from JWT signing. */
@Service
public class SecretEncryptionService {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec currentKey;
    private final SecretKeySpec legacyKey;

    @Autowired
    public SecretEncryptionService(
            @Value("${app.security.encryption-key}") String encryptionKey,
            @Value("${app.security.legacy-encryption-key:}") String legacyEncryptionKey,
            @Value("${jwt.secret}") String jwtSecret) {
        String validatedKey = requireKey(encryptionKey, "APP_SECURITY_ENCRYPTION_KEY");
        if (jwtSecret != null && MessageDigest.isEqual(
                validatedKey.getBytes(StandardCharsets.UTF_8), jwtSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                    "APP_SECURITY_ENCRYPTION_KEY must be different from JWT_SECRET");
        }
        this.currentKey = deriveKey(validatedKey);
        this.legacyKey = legacyEncryptionKey == null || legacyEncryptionKey.isBlank()
                ? null : deriveKey(requireKey(legacyEncryptionKey, "APP_SECURITY_LEGACY_ENCRYPTION_KEY"));
    }

    /** Explicit-key constructor for isolated unit tests; Spring always uses the validated 3-arg constructor. */
    public SecretEncryptionService(String encryptionKey, String legacyEncryptionKey) {
        this.currentKey = deriveKey(requireKey(encryptionKey, "test encryption key"));
        this.legacyKey = legacyEncryptionKey == null || legacyEncryptionKey.isBlank()
                ? null : deriveKey(requireKey(legacyEncryptionKey, "test legacy encryption key"));
    }

    private static String requireKey(String value, String name) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(name + " must contain at least 32 UTF-8 bytes and be different from JWT_SECRET");
        }
        return value;
    }

    private SecretKeySpec deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("aloute-totp:" + secret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot initialize AES encryption", exception);
        }
    }

    public String encrypt(String value) {
        if (value == null) throw new IllegalArgumentException("value is required");
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] output = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot encrypt account-security data", exception);
        }
    }

    public String decrypt(String value) {
        return decryptWithKey(value, currentKey);
    }

    /**
     * Used only by the one-time migration runner: if the ciphertext is not readable with the new
     * key, decrypt it with the explicitly supplied legacy key and return a newly encrypted value.
     */
    public Optional<String> reencryptLegacyValueIfNeeded(String value) {
        if (value == null || value.isBlank() || legacyKey == null || sameKey(currentKey, legacyKey)) {
            return Optional.empty();
        }
        try {
            decryptWithKey(value, currentKey);
            return Optional.empty();
        } catch (IllegalStateException notCurrentKey) {
            String plaintext = decryptWithKey(value, legacyKey);
            return Optional.of(encrypt(plaintext));
        }
    }

    public boolean hasLegacyKey() {
        return legacyKey != null && !sameKey(currentKey, legacyKey);
    }

    private boolean sameKey(SecretKeySpec first, SecretKeySpec second) {
        return first != null && second != null && MessageDigest.isEqual(first.getEncoded(), second.getEncoded());
    }

    private String decryptWithKey(String value, SecretKeySpec key) {
        try {
            byte[] input = Base64.getDecoder().decode(value);
            if (input.length < NONCE_LENGTH + 1) throw new IllegalArgumentException("Ciphertext too short");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(input, NONCE_LENGTH)));
            return new String(cipher.doFinal(input, NONCE_LENGTH, input.length - NONCE_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot decrypt account-security data; check APP_SECURITY_ENCRYPTION_KEY", exception);
        }
    }
}
