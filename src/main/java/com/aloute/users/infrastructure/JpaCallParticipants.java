package com.aloute.users.infrastructure;

import com.aloute.repository.IUserRepository;
import com.aloute.users.api.CallParticipants;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class JpaCallParticipants implements CallParticipants {
    private final IUserRepository users;

    public JpaCallParticipants(IUserRepository users) { this.users = users; }

    public Map<Long, Participant> lookup(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        var ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, Participant> result = new HashMap<>();
        for (int start = 0; start < ids.size(); start += 500) {
            users.findAllById(ids.subList(start, Math.min(start + 500, ids.size()))).forEach(user ->
                    result.put(user.getId(), new Participant(user.getId(), user.getUsername(),
                            user.getFullName(), user.getAvatarUrl())));
        }
        return Map.copyOf(result);
    }
}
