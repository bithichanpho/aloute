package com.aloute.users.infrastructure;

import com.aloute.repository.IUserRepository;
import com.aloute.users.api.UserDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Users owns persistence; consumers receive an immutable, minimal projection. */
@Service
public class JpaUserDirectory implements UserDirectory {
    private final IUserRepository users;

    public JpaUserDirectory(IUserRepository users) {
        this.users = users;
    }

    @Override
    public boolean exists(Long userId) { return userId != null && users.existsById(userId); }

    @Override
    public Optional<String> username(Long userId) {
        return userId == null ? Optional.empty() : users.findDirectoryUsername(userId);
    }

    @Override
    public Map<Long, String> usernames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        var ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> result = new HashMap<>();
        // Bound SQL IN clauses even when a future caller requests a large directory batch.
        for (int start = 0; start < ids.size(); start += 500) {
            users.findDirectoryEntries(ids.subList(start, Math.min(start + 500, ids.size())))
                    .forEach(entry -> result.put(entry.getId(), entry.getUsername()));
        }
        return Map.copyOf(result);
    }
}
