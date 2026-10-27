package com.aloute.social.infrastructure;

import com.aloute.service.ContentAccessService;
import com.aloute.social.api.FollowRequestLookup;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class LegacyFollowRequestLookup implements FollowRequestLookup {
    private final ContentAccessService access;
    public LegacyFollowRequestLookup(ContentAccessService access) { this.access = access; }
    public Set<String> pendingIds(Collection<String> references) {
        return access.pendingFollowRequestIds(references);
    }
}
