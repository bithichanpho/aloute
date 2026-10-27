package com.aloute.posts.infrastructure;

import com.aloute.posts.api.PostNotificationLookup;
import com.aloute.repository.IPostRepository;
import com.aloute.service.ContentAccessService;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class NotificationPostReader implements PostNotificationLookup {
    private final IPostRepository posts;
    private final ContentAccessService access;
    private final com.aloute.communities.api.CommunityReviewAccess communityAccess;

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationPostReader(IPostRepository posts, ContentAccessService access,
            com.aloute.communities.api.CommunityReviewAccess communityAccess) {
        this.posts = posts;
        this.access = access;
        this.communityAccess = communityAccess;
    }

    /** Compatibility construction has no review privileges without the community owner. */
    public NotificationPostReader(IPostRepository posts, ContentAccessService access) {
        this(posts, access, null);
    }

    public Optional<Long> ownerOf(String postId) {
        return posts.findById(postId).map(post -> Long.valueOf(post.getUserId()));
    }

    public java.util.Map<String, Long> ownersOf(java.util.Collection<String> postIds) {
        if (postIds == null || postIds.isEmpty()) return java.util.Map.of();
        var ids = postIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        var owners = new java.util.HashMap<String, Long>();
        for (int start = 0; start < ids.size(); start += 500) {
            for (var post : posts.findAllById(ids.subList(start, Math.min(start + 500, ids.size()))))
                owners.put(post.getId(), Long.valueOf(post.getUserId()));
        }
        return java.util.Map.copyOf(owners);
    }

    public boolean isReadable(String postId, Long viewerId) {
        return postId != null && posts.findById(postId)
                .filter(post -> access.canRead(post, viewerId)).isPresent();
    }

    public boolean canReviewCommunityPost(String postId, Long viewerId) {
        return communityAccess != null && postId != null && posts.findById(postId)
                .filter(post -> post.getCommunityPostStatus() == com.aloute.model.document.CommunityPostStatus.PENDING)
                .filter(post -> communityAccess.mayReviewPosts(post.getCommunityId(), viewerId)).isPresent();
    }
}
