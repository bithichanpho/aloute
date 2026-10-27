package com.aloute.users.api;

import com.aloute.common.util.UsernamePolicy;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/** Local presentation URLs only; database IDs remain the identity for commands and relationships. */
public final class ProfileLinks {
    private ProfileLinks() {}

    public static boolean isHandle(String username) {
        // @ also supports existing accounts whose legacy username is an email address.
        return username != null && username.length() <= 255 && username.matches("[A-Za-z0-9_.@-]+")
                && !UsernamePolicy.isReserved(username);
    }

    public static String path(String username, Long userId) {
        if (isHandle(username)) return "/@" + UriUtils.encode(username, StandardCharsets.UTF_8);
        return userId != null && userId > 0 ? "/profile/" + userId : "#";
    }
}
