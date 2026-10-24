package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TotpServiceTest {
    private final TotpService totp = new TotpService();

    @Test
    void matchesRfc6238Sha1VectorsForSixDigits() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        long[] timestamps = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int i = 0; i < timestamps.length; i++)
            assertEquals(expected[i], totp.code(secret, timestamps[i] / 30));
    }

    @Test
    void rejectsMissingAndMalformedCodes() {
        assertEquals(-1, totp.verify(null, "123456"));
        assertEquals(-1, totp.verify(totp.newSecret(), "abcdef"));
        assertEquals(-1, totp.verify(totp.newSecret(), "12345"));
    }

    @Test
    void secretsAreRandomAndValid() {
        String secret = totp.newSecret();
        assertTrue(secret.matches("[A-Z2-7]{32}"));
        assertNotEquals(secret, totp.newSecret());
        long step = java.time.Instant.now().getEpochSecond() / 30;
        assertEquals(step, totp.verify(secret, totp.code(secret, step)));
    }

    @Test
    void encryptionUsesUniqueNonceAndDetectsTampering() {
        var encryption = new SecretEncryptionService("unit-test-aes-encryption-key-at-least-32-bytes", "");
        String secret = totp.newSecret();
        String encoded = encryption.encrypt(secret);
        assertEquals(secret, encryption.decrypt(encoded));
        assertNotEquals(encoded, encryption.encrypt(secret));
        byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
        bytes[15] ^= 1;
        assertThrows(
                IllegalStateException.class,
                () -> encryption.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)));
    }
    @Test
    void encryptionKeyCannotBeTheSameAsJwtSigningKey() {
        String shared = "same-random-key-longer-than-32-bytes-for-test";
        assertThrows(IllegalStateException.class,
                () -> new SecretEncryptionService(shared, "", shared));
    }

}
