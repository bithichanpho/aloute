package com.aloute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

class AuthTemplateCspTest {

    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);

    @Test
    void authPagesUseExternalScriptsAllowedByContentSecurityPolicy() throws IOException {
        Map<String, String> scripts =
                Map.of(
                        "register", "auth-register.js",
                        "verify", "auth-verify.js",
                        "login", "auth-login.js",
                        "forgot-password", "auth-forgot-password.js",
                        "reset-password", "auth-reset-password.js");

        for (var entry : scripts.entrySet()) {
            String html = readTemplate(entry.getKey());
            assertTrue(html.contains("theme-init.js"), entry.getKey());
            assertTrue(html.contains(entry.getValue()), entry.getKey());
            assertFalse(INLINE_SCRIPT.matcher(html).find(), entry.getKey());
        }
    }

    private String readTemplate(String name) throws IOException {
        String path = "templates/auth/" + name + ".html";
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
