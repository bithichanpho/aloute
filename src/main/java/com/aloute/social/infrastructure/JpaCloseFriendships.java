package com.aloute.social.infrastructure;

import com.aloute.model.entity.CloseFriend;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.social.api.CloseFriendships;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class JpaCloseFriendships implements CloseFriendships {
    private final ICloseFriendRepository relationships;
    public JpaCloseFriendships(ICloseFriendRepository relationships) { this.relationships = relationships; }

    public Set<Long> acceptedAmong(Long viewerId, Collection<Long> candidateIds) {
        if (viewerId == null || candidateIds == null || candidateIds.isEmpty()) return Set.of();
        var ids = candidateIds.stream().filter(Objects::nonNull)
                .filter(id -> !viewerId.equals(id)).distinct().toList();
        Set<Long> accepted = new HashSet<>();
        for (int start = 0; start < ids.size(); start += 500) {
            var chunk = ids.subList(start, Math.min(start + 500, ids.size()));
            for (var relationship : relationships.findRelationships(viewerId, chunk)) {
                if (!CloseFriend.ACCEPTED.equals(relationship.getStatus())) continue;
                if (!viewerId.equals(relationship.getRequesterId())
                        && !viewerId.equals(relationship.getReceiverId())) continue;
                Long other = viewerId.equals(relationship.getRequesterId())
                        ? relationship.getReceiverId() : relationship.getRequesterId();
                if (chunk.contains(other)) accepted.add(other);
            }
        }
        return Set.copyOf(accepted);
    }
}
