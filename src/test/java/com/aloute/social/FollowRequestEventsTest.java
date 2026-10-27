package com.aloute.social;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.social.infrastructure.WebSocketFollowRequestEvents;
import com.aloute.users.api.UserDirectory;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class FollowRequestEventsTest {
    @Test
    void publishesOnlyToTheResolvedRecipientsUserQueue() {
        var users = mock(UserDirectory.class); var socket = mock(SimpMessagingTemplate.class);
        when(users.username(2L)).thenReturn(Optional.of("recipient"));
        when(users.username(1L)).thenReturn(Optional.of("sender"));
        new WebSocketFollowRequestEvents(users, socket).publish(2L, "REQUEST_CREATED", 1L, 2L, "PENDING");
        verify(socket).convertAndSendToUser(eq("recipient"), eq("/queue/follow-requests"),
                argThat((Object value) -> value instanceof java.util.Map<?,?> data
                        && data.get("followerId").equals(1L) && data.get("targetId").equals(2L)));
        verifyNoMoreInteractions(socket);
    }

    @Test
    void transientSocketFailureDoesNotFailTheAlreadyCommittedAction() {
        var users = mock(UserDirectory.class); var socket = mock(SimpMessagingTemplate.class);
        when(users.username(any())).thenReturn(Optional.of("recipient"));
        doThrow(new IllegalStateException("offline")).when(socket).convertAndSendToUser(anyString(), anyString(), any(Object.class));
        assertDoesNotThrow(() -> new WebSocketFollowRequestEvents(users, socket)
                .publish(2L, "REQUEST_DECIDED", 1L, 2L, "NONE"));
    }
}
