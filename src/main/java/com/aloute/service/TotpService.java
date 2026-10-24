package com.aloute.service;

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class TotpService {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public String newSecret() {
        SecureRandom random = new SecureRandom();
        StringBuilder secret = new StringBuilder();
        for (int i = 0; i < 32; i++) secret.append(ALPHABET.charAt(random.nextInt(32)));
        return secret.toString();
    }

    public String code(String secret, long step) {
        try {
            byte[] key = new byte[secret.length() * 5 / 8];
            int buffer = 0, bits = 0, index = 0;
            for (char c : secret.toCharArray()) {
                int value = ALPHABET.indexOf(c);
                if (value < 0) throw new IllegalArgumentException("Invalid TOTP secret");
                buffer = (buffer << 5) | value;
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    key[index++] = (byte) (buffer >> bits);
                }
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 15;
            int value = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
            return String.format(java.util.Locale.ROOT, "%06d", value % 1000000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public long verify(String secret, String code) {
        if (secret == null || code == null || !code.matches("[0-9]{6}")) return -1;
        long step = Instant.now().getEpochSecond() / 30;
        for (long candidate = step - 1; candidate <= step + 1; candidate++)
            if (java.security.MessageDigest.isEqual(
                    code(secret, candidate).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    code.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return candidate;
        return -1;
    }
}
