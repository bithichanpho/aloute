package com.aloute;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RealtimeDependencyTest {
    @Test
    void loaderOnlyUsesPinnedLocalLibrariesAllowedBySelfCsp() throws Exception {
        String app = new String(resource("static/js/app.js"), StandardCharsets.UTF_8);
        assertTrue(app.contains("'/vendor/sockjs-client/sockjs.min.js'"));
        assertTrue(app.contains("'/vendor/stomp/stomp.min.js'"));
        assertFalse(app.contains("cdnjs.cloudflare.com"));
        assertDigest("static/vendor/sockjs-client/sockjs.min.js", "1e0f37d073666196b0848fd7d80d525268d5adf47ea1ec459632f05bbf8e1f54");
        assertDigest("static/vendor/stomp/stomp.min.js", "9e43fc723e716937562bf06c665fb9ed9084fd8fe2e1436d6cd4e9807fba4dac");
    }

    private void assertDigest(String resource, String expected) throws Exception {
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource(resource))));
    }
    private byte[] resource(String name) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name); return input.readAllBytes();
        }
    }
}
