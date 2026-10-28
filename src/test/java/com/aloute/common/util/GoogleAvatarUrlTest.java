package com.aloute.common.util;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoogleAvatarUrlTest {
    @Test void googleAvatarsUseOneLocalSourceAndResolvingAgainDoesNotChangeIt() {
        String google = "https://lh3.googleusercontent.com/a/example=s96-c";
        String local = AvatarUtil.resolve(google);
        assertTrue(local.startsWith("/images/avatars/google?url="));
        assertEquals(google, URLDecoder.decode(local.substring(local.indexOf('=') + 1), StandardCharsets.UTF_8));
        assertEquals(local, AvatarUtil.resolve(local));
        assertEquals(AvatarUtil.DEFAULT_AVATAR, AvatarUtil.resolve(null));
        assertEquals(AvatarUtil.DEFAULT_AVATAR, AvatarUtil.resolve("  "));
        assertEquals("/uploads/avatar.png", AvatarUtil.resolve("/uploads/avatar.png"));
        assertTrue(GoogleAvatarUrl.isSupported("https://lh6.googleusercontent.com/a-/example"));
    }

    @Test void proxyRejectsOtherOriginsCredentialsPortsAndPaths() {
        for (String url : List.of("http://lh3.googleusercontent.com/a/image",
                "https://lh3.googleusercontent.com.evil.test/a/image", "https://127.0.0.1/a/image",
                "https://lh3.googleusercontent.com:8443/a/image", "https://user@lh3.googleusercontent.com/a/image",
                "https://lh3.googleusercontent.com/other/image", "https://lh3.googleusercontent.com/a/../image",
                "https://lh3.googleusercontent.com/a/image#fragment", "not a url")) {
            assertFalse(GoogleAvatarUrl.isSupported(url), url);
        }
    }
}
