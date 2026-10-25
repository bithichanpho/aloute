package com.aloute.users.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Internal lookup only: never exposes a User entity, email, password or token. */
public interface UserDirectory {
    Optional<String> username(Long userId);
    boolean exists(Long userId);

    Map<Long, String> usernames(Collection<Long> userIds);
}
