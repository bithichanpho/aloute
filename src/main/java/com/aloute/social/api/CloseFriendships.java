package com.aloute.social.api;

import java.util.Collection;
import java.util.Set;

/** Current accepted close-friend relationships; pending invitations never grant access. */
public interface CloseFriendships {
    Set<Long> acceptedAmong(Long viewerId, Collection<Long> candidateIds);
}
