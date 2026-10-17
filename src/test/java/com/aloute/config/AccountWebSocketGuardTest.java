package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AccountWebSocketGuardTest {
    @Test
    void rejectsStompCommandsWithExpiredHandshakeToken() {
        IUserRepository users = mock(IUserRepository.class);
        User user = new User();
        user.setId(9L);
        user.setUsername("socket-user");
        user.setPassword("unused");
        user.setFullName("Socket User");
        user.setRole(new Role("ROLE_USER"));
        when(users.findByUsername("socket-user")).thenReturn(Optional.of(user));

        AccountWebSocketGuard guard = new AccountWebSocketGuard(users);
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setUser(new UsernamePasswordAuthenticationToken(
                "socket-user", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        headers.setDestination("/app/chat.send");
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put("tokenIssuedAt", Instant.now().minusSeconds(3600));
        attributes.put("tokenExpiresAt", Instant.now().minusSeconds(1));
        headers.setSessionAttributes(attributes);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());

        assertThrows(MessageDeliveryException.class, () -> guard.preSend(message, mock(MessageChannel.class)));
    }
}
