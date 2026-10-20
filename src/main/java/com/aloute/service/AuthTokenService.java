package com.aloute.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthTokenService {

    /** Number of digits in an emailed one-time code (OTP). */
    public static final int OTP_LENGTH = 6;

    private static final Pattern OTP_FORMAT = Pattern.compile("\\d{" + OTP_LENGTH + "}");

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }

        return sha256Hex(token.trim());
    }

    /** Generates a uniformly distributed, zero-padded numeric code such as {@code 048213}. */
    public String generateOtp() {
        int bound = (int) Math.pow(10, OTP_LENGTH);

        return String.format("%0" + OTP_LENGTH + "d", secureRandom.nextInt(bound));
    }

    /** True when the value is exactly {@value #OTP_LENGTH} ASCII digits. */
    public boolean isValidOtpFormat(String otp) {
        return otp != null && OTP_FORMAT.matcher(otp).matches();
    }

    /**
     * Hashes an OTP together with the email it was issued to. A six-digit code has only a million
     * possible values, so binding it to the email keeps equal codes of different people from
     * producing equal hashes (the verification column is unique) and means a stored hash is only
     * useful together with the matching email address.
     */
    public String hashOtp(String email, String otp) {
        if (email == null || email.isBlank() || otp == null || otp.isBlank()) {
            return "";
        }

        return sha256Hex(email.trim().toLowerCase(Locale.ROOT) + ":" + otp.trim());
    }

    /** Constant-time comparison of a submitted OTP against the stored hash for that email. */
    public boolean otpMatches(String email, String otp, String storedHash) {
        if (storedHash == null || storedHash.isBlank() || !isValidOtpFormat(otp == null ? null : otp.trim())) {
            return false;
        }

        byte[] expected = storedHash.getBytes(StandardCharsets.UTF_8);
        byte[] actual = hashOtp(email, otp).getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(expected, actual);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Không thể khởi tạo SHA-256", e);
        }
    }
}
