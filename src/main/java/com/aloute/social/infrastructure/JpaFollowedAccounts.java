package com.aloute.social.infrastructure;

import com.aloute.repository.IFriendRepository;
import com.aloute.social.api.FollowedAccounts;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class JpaFollowedAccounts implements FollowedAccounts {
    private final IFriendRepository friends;

    public JpaFollowedAccounts(IFriendRepository friends) {
        this.friends = friends;
    }

    public List<Long> followedIdsAfter(Long viewerId, Long afterId, int limit) {
        return List.copyOf(friends.findFollowedIdsAfter(viewerId, afterId,
                PageRequest.of(0, Math.max(1, Math.min(500, limit)))));
    }

    public boolean hasFollowing(Long viewerId) {
        return viewerId != null && friends.existsByFollowerId(viewerId);
    }
}
