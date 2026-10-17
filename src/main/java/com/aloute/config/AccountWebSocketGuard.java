package com.aloute.config;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.aloute.service.AuthenticationUserCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class AccountWebSocketGuard implements ChannelInterceptor {
    private final AuthenticationUserCache authenticationUserCache;

    @Autowired
    public AccountWebSocketGuard(AuthenticationUserCache authenticationUserCache) {
        this.authenticationUserCache = authenticationUserCache;
    }

    /** Compatibility constructor for focused tests; production uses the shared cache bean. */
    public AccountWebSocketGuard(IUserRepository users) {
        this(new AuthenticationUserCache(users, java.time.Duration.ofSeconds(5)));
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null || headers.getCommand() == StompCommand.DISCONNECT) {
            return message;
        }
        var attributes = headers.getSessionAttributes();
        Object authFailure = attributes == null ? null : attributes.get(JwtHandshakeInterceptor.ATTR_AUTH_FAILURE);
        if (authFailure != null) throw new MessageDeliveryException(authFailure.toString());
        if (headers.getUser() == null)
            throw new MessageDeliveryException("Cần đăng nhập; access token may have expired");

        // Cache lookup is local and bounded; it replaces a SQL query on every typing/message frame.
        User user = authenticationUserCache.findByUsername(headers.getUser().getName())
                .filter(User::isActive)
                .orElseThrow(() -> new MessageDeliveryException("Tài khoản không hoạt động"));
        Object issued = attributes == null ? null : attributes.get("tokenIssuedAt");
        Object expires = attributes == null ? null : attributes.get("tokenExpiresAt");
        if (!(expires instanceof Instant expiry) || !expiry.isAfter(Instant.now())) {
            throw new MessageDeliveryException("Access token đã hết hạn; cần làm mới phiên đăng nhập");
        }
        if (user.getTokensRevokedAt() != null
                && (!(issued instanceof Instant instant) || !instant.isAfter(user.getTokensRevokedAt()))) {
            throw new MessageDeliveryException("Phiên đăng nhập đã hết hiệu lực");
        }
        if (headers.getCommand() == StompCommand.SUBSCRIBE
                && (headers.getDestination() == null || !headers.getDestination().startsWith("/user/queue/"))) {
            throw new MessageDeliveryException("Không có quyền truy cập kênh");
        }
        if (headers.getCommand() == StompCommand.SEND
                && (headers.getDestination() == null || !headers.getDestination().startsWith("/app/"))) {
            throw new MessageDeliveryException("Không có quyền gửi đến kênh");
        }
        return message;
    }
}
