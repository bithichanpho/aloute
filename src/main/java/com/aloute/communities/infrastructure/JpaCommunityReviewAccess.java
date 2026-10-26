package com.aloute.communities.infrastructure;

import com.aloute.communities.api.CommunityReviewAccess;
import com.aloute.communities.domain.CommunityRoles;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import org.springframework.stereotype.Service;

@Service
public class JpaCommunityReviewAccess implements CommunityReviewAccess {
    private final ICommunityRepository communities;
    private final ICommunityMemberRepository members;

    public JpaCommunityReviewAccess(ICommunityRepository communities, ICommunityMemberRepository members) {
        this.communities = communities;
        this.members = members;
    }

    public boolean mayReviewPosts(String communityId, Long userId) {
        if (communityId == null || communityId.isBlank() || userId == null) return false;
        final Long id;
        try { id = Long.valueOf(communityId.trim()); }
        catch (NumberFormatException invalid) { return false; }
        return communities.existsById(id)
                && members.existsByCommunityIdAndUserIdAndRoleIn(id, userId, CommunityRoles.managerRoles());
    }
}
