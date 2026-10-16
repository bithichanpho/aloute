package com.aloute.config;

import com.aloute.repository.IUserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountWebSocketGuard implements ChannelInterceptor {
    private final IUserRepository users;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null
                || headers.getCommand() == null
                || headers.getCommand() == StompCommand.DISCONNECT) return message;
        if (headers.getUser() == null) throw new MessageDeliveryException("Cần đăng nhập");
        var user =
                users.findByUsername(headers.getUser().getName())
                        .filter(u -> u.isActive())
                        .orElseThrow(
                                () -> new MessageDeliveryException("Tài khoản không hoạt động"));
        var attributes = headers.getSessionAttributes();
        Object issued = attributes == null ? null : attributes.get("tokenIssuedAt");
        if (user.getTokensRevokedAt() != null
                && (!(issued instanceof java.time.Instant instant)
                        || !instant.isAfter(user.getTokensRevokedAt())))
            throw new MessageDeliveryException("Phiên đăng nhập đã hết hiệu lực");
        if (headers.getCommand() == StompCommand.SUBSCRIBE
                && (headers.getDestination() == null
                        || !headers.getDestination().startsWith("/user/queue/")))
            throw new MessageDeliveryException("Không có quyền truy cập kênh");
        if (headers.getCommand() == StompCommand.SEND
                && (headers.getDestination() == null
                        || !headers.getDestination().startsWith("/app/")))
            throw new MessageDeliveryException("Không có quyền gửi đến kênh");
        return message;
    }
}
