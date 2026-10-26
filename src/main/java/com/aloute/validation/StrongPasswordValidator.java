package com.aloute.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || value.length() < 8 || value.length() > 72) {
            return false;
        }
        // BCrypt's bound is UTF-8 bytes, not Java UTF-16 length. Reject before encoding;
        // never truncate or normalize a password, which would change its identity.
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            if (context != null) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("Mật khẩu không được vượt quá 72 byte UTF-8")
                        .addConstraintViolation();
            }
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            upper |= Character.isUpperCase(codePoint);
            lower |= Character.isLowerCase(codePoint);
            digit |= Character.isDigit(codePoint);
            special |= !Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint);
            offset += Character.charCount(codePoint);
        }
        return upper && lower && digit && special;
    }
}
