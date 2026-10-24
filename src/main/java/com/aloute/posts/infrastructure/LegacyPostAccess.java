package com.aloute.posts.infrastructure;

import com.aloute.model.document.Post;
import com.aloute.posts.domain.PostAccess;
import com.aloute.service.ContentAccessService;
import org.springframework.stereotype.Service;

/** Keeps the tested block/private/community policy authoritative during the migration. */
@Service
public class LegacyPostAccess implements PostAccess {
    private final ContentAccessService access;

    public LegacyPostAccess(ContentAccessService access) { this.access = access; }

    public Post requireReadable(String postId, Long viewerId) { return access.requirePost(postId, viewerId); }
}
