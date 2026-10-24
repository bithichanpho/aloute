package com.aloute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.AuthenticationUserCache;
import com.aloute.service.CallTimeoutService;
import com.aloute.service.ChatService;
import com.aloute.service.ContentAccessService;
import com.aloute.service.FriendService;
import com.aloute.service.NotificationService;
import com.aloute.service.PresenceService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** STOMP frames must be resolved from the short-lived user cache, not from PostgreSQL per frame. */
class ChatControllerStompTest {
    private IUserRepository users;
    private ChatService chatService;
    private SimpMessagingTemplate messaging;
    private ContentAccessService access;
    private ChatController controller;
    private final Principal alice = () -> "alice";

    @BeforeEach
    void setUp() {
        users = mock(IUserRepository.class);
        chatService = mock(ChatService.class);
        messaging = mock(SimpMessagingTemplate.class);
        access = mock(ContentAccessService.class);

        User sender = new User();
        sender.setId(1L);
        sender.setUsername("alice");
        sender.setActive(true);
        User receiver = new User();
        receiver.setId(2L);
        receiver.setUsername("bob");
        receiver.setActive(true);
        when(users.findByUsername("alice")).thenReturn(Optional.of(sender));
        when(users.findById(2L)).thenReturn(Optional.of(receiver));
        when(access.profileVisible(1L, 2L)).thenReturn(true);

        controller = new ChatController(
                chatService,
                new AuthenticationUserCache(users, Duration.ofSeconds(30)),
                messaging,
                mock(PresenceService.class),
                mock(FriendService.class),
                mock(NotificationService.class),
                mock(CallTimeoutService.class),
                access,
                mock(Validator.class),
                mock(AuthRateLimitService.class));
    }

    @Test
    void repeatedTypingFramesResolveSenderAndReceiverOnlyOnce() {
        for (int i = 0; i < 3; i++) {
            controller.typing(new ChatController.TypingSignal(2L), alice);
        }

        verify(users, times(1)).findByUsername("alice");
        verify(users, times(1)).findById(2L);
        verify(messaging, times(3))
                .convertAndSendToUser("bob", "/queue/typing", Map.of("senderId", 1L));
    }

    @Test
    void repeatedDeliveryAcksResolveTheSenderOnlyOnce() {
        for (int i = 0; i < 3; i++) {
            controller.acknowledgeDelivered(new ChatController.DeliverySignal("message-1"), alice);
        }

        verify(users, times(1)).findByUsername("alice");
        verify(chatService, times(3)).markAsDelivered(1L, "message-1");
    }

    @Test
    void anonymousSocketsNeverTouchTheUserStore() {
        Principal anonymous = () -> "anonymous-1234";
        controller.typing(new ChatController.TypingSignal(2L), anonymous);
        controller.acknowledgeDelivered(new ChatController.DeliverySignal("message-1"), anonymous);

        verify(users, never()).findByUsername(any());
        verifyNoInteractions(messaging);
        verifyNoInteractions(chatService);
    }
}
