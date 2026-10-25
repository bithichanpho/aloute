package com.aloute.social.infrastructure;

import com.aloute.service.ContentAccessService;
import com.aloute.social.api.ProfileVisibility;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Transition bridge: the existing policy remains authoritative during incremental migration. */
@Service
public class LegacyProfileVisibility implements ProfileVisibility {
    private final ContentAccessService access;

    public LegacyProfileVisibility(ContentAccessService access) {
        this.access = access;
    }

    public boolean profileVisible(Long viewerId, Long authorId) {
        return access.profileVisible(viewerId, authorId);
    }

    public Set<Long> visibleProfileIds(Long viewerId, Collection<Long> authorIds) {
        return access.visibleProfileIds(viewerId, authorIds);
    }
}
