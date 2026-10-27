package com.aloute.social.api;

import java.util.Collection;
import java.util.Set;

public interface FollowRequestLookup {
    Set<String> pendingIds(Collection<String> references);
}
