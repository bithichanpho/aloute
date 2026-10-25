package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.messaging.api.MissedCallHistory;
import com.aloute.messaging.calls.application.CallRegistryService;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.Notifications;
import com.aloute.repository.IUserRepository;
import com.aloute.service.*;
import com.aloute.users.api.CallParticipants;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class CallSignalingContractTest {
    @Test
    void offerUsesServerCallerIdentityAndExistingDestinationAndPayloadFields() {
        Fixture f = new Fixture();
        f.controller.callOffer(f.signal(2L), () -> "alice");
        var sent = ArgumentCaptor.forClass(Object.class);
        verify(f.ws).convertAndSendToUser(eq("bob"), eq("/queue/call"), sent.capture());
        Map<?, ?> payload = (Map<?, ?>) sent.getValue();
        assertEquals(Set.of("sdp", "candidate", "targetUserId", "callerUserId", "callId", "type", "fromUserId",
                "fromUsername", "fromFullName", "fromAvatarUrl", "callType"), payload.keySet());
        assertEquals(1L, payload.get("callerUserId"));
        assertEquals(1L, payload.get("fromUserId"));
        assertEquals("alice", payload.get("fromUsername"));
        assertEquals("offer", payload.get("type"));
        assertEquals("video", payload.get("callType"));
        assertTrue(f.calls.findCall("c").isPresent());
    }

    @Test
    void answerIceAndHangupUseTheSameRegistryAndRejectThirdPartyAndDuplicateHangup() {
        Fixture f = new Fixture();
        f.calls.registerOffer("c", 1L, 2L, "video");
        f.controller.callAnswer(f.signal(1L), () -> "bob");
        f.controller.callIce(f.signal(2L), () -> "alice");
        f.controller.callHangup(f.signal(2L), () -> "charlie");
        assertTrue(f.calls.findCall("c").isPresent());
        f.controller.callHangup(f.signal(1L), () -> "bob");
        f.controller.callHangup(f.signal(1L), () -> "bob");
        assertTrue(f.calls.findCall("c").isEmpty());
        verify(f.ws, times(2)).convertAndSendToUser(eq("alice"), eq("/queue/call"), any());
        verify(f.ws).convertAndSendToUser(eq("bob"), eq("/queue/call"), any());
        verifyNoMoreInteractions(f.ws);
    }

    @Test
    void spoofedMissedFlagCannotCreateNotificationAndFailedHangupSendDoesNotRestoreState() {
        Fixture f = new Fixture();
        f.calls.registerOffer("c", 1L, 2L, "video");
        doThrow(new IllegalStateException("test broker outage")).when(f.ws)
                .convertAndSendToUser(anyString(), eq("/queue/call"), any());
        var spoofed = new ChatController.CallSignal(2L, 99L, "c", "video", true,
                null, null, null, null);
        assertDoesNotThrow(() -> f.controller.callHangup(spoofed, () -> "alice"));
        assertTrue(f.calls.findCall("c").isEmpty());
        verifyNoInteractions(f.notifications);
    }

    @Test
    void blockedParticipantCanFinishOwnedCallWithoutDeliveringPrivateSignal() {
        Fixture f = new Fixture();
        f.calls.registerOffer("c", 1L, 2L, "video");
        when(f.access.profileVisible(1L, 2L)).thenReturn(false);
        f.controller.callHangup(f.signal(2L), () -> "alice");
        assertTrue(f.calls.findCall("c").isEmpty());
        verifyNoInteractions(f.ws, f.notifications);
    }

    @Test
    void blockedPeerCannotRegisterOrReceiveOffer() {
        Fixture f = new Fixture();
        when(f.access.profileVisible(1L, 2L)).thenReturn(false);
        f.controller.callOffer(f.signal(2L), () -> "alice");
        assertTrue(f.calls.findCall("c").isEmpty());
        verifyNoInteractions(f.ws);
    }

    private static class Fixture {
        final SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        final ContentAccessService access = mock(ContentAccessService.class);
        final Notifications notifications = mock(Notifications.class);
        final CallRegistryService calls;
        final ChatController controller;

        Fixture() {
            var users = mock(IUserRepository.class);
            for (long id = 1; id <= 3; id++) {
                User user = new User(); user.setId(id);
                user.setUsername(List.of("alice", "bob", "charlie").get((int) id - 1));
                when(users.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
                when(users.findById(id)).thenReturn(Optional.of(user));
            }
            when(access.profileVisible(anyLong(), anyLong())).thenReturn(true);
            FriendService friends = mock(FriendService.class);
            when(friends.isFriend(anyLong(), anyLong())).thenReturn(true);
            calls = new CallRegistryService(mock(ScheduledExecutorService.class), ws,
                    mock(CallParticipants.class), notifications, mock(MissedCallHistory.class), null, false);
            controller = new ChatController(mock(ChatService.class), new AuthenticationUserCache(users, Duration.ofSeconds(30)),
                    ws, mock(PresenceService.class), friends, notifications, calls, access,
                    mock(Validator.class), mock(AuthRateLimitService.class));
        }

        ChatController.CallSignal signal(Long target) {
            return new ChatController.CallSignal(target, 99L, "c", "video", false,
                    null, null, Map.of("candidate", "test"), Map.of("type", "offer"));
        }
    }
}
