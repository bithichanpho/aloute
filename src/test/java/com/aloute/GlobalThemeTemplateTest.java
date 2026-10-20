package com.aloute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

class GlobalThemeTemplateTest {

    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);

    @Test
    void everyStandalonePageLoadsTheSameCspCompatibleThemeInitializer() throws IOException {
        List<String> templates = List.of(
                "templates/layout/base.html",
                "templates/auth/register.html",
                "templates/auth/login.html",
                "templates/auth/verify.html",
                "templates/auth/forgot-password.html",
                "templates/auth/reset-password.html",
                "templates/error/403.html",
                "templates/error/404.html",
                "templates/error/500.html");

        for (String path : templates) {
            String html = readResource(path);
            assertTrue(html.contains("theme-init.js"), path);
            assertFalse(html.contains("auth-theme-init.js"), path);
            assertFalse(INLINE_SCRIPT.matcher(html).find(), path);
        }
    }

    private String readResource(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
