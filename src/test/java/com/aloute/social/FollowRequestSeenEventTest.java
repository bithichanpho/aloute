package com.aloute.social;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.users.api.UserDirectory;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class FollowRequestSeenEventTest {
    @Test
    void inboxSeenHintIsSentOnlyToItsOwnerWithoutChangingFollowStatus() {
        var users = mock(UserDirectory.class);
        var messaging = mock(SimpMessagingTemplate.class);
        when(users.username(1L)).thenReturn(Optional.of("alice"));
        new FollowRequestRealtimePublisher(users, messaging).publishSeen(1L);
        verify(messaging).convertAndSendToUser(eq("alice"), eq("/queue/follow-requests"),
                argThat((Map<String, Object> payload) -> "REQUESTS_SEEN".equals(payload.get("eventType"))
                        && Long.valueOf(1L).equals(payload.get("targetId"))
                        && !payload.containsKey("status") && !payload.containsKey("followerId")));
        verifyNoMoreInteractions(messaging);
        verify(users).username(1L);
        verifyNoMoreInteractions(users);
    }
}
