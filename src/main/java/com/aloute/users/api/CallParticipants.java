package com.aloute.users.api;

import java.util.Collection;
import java.util.Map;

/** Only the profile fields already present in call signaling; credentials never cross this API. */
public interface CallParticipants {
    record Participant(Long id, String username, String fullName, String avatarUrl) {}
    Map<Long, Participant> lookup(Collection<Long> userIds);
}
