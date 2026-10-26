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
