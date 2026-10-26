package com.aloute.config;

import com.aloute.service.PresenceService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Component
public class WebSocketEventListener {
    private final PresenceService presenceService;

    public WebSocketEventListener(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @EventListener
    public void handleConnected(SessionConnectedEvent event) {
        Principal user = event.getUser();
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (isRealUser(user) && sessionId != null && !sessionId.isBlank()) {
            presenceService.userConnected(user.getName(), sessionId);
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        Principal user = event.getUser();
        // Let the service resolve the username from its session map if the disconnect event lacks a principal.
        presenceService.userDisconnected(
                isRealUser(user) ? user.getName() : null,
                event.getSessionId());
    }

    private boolean isRealUser(Principal user) {
        return user != null && user.getName() != null && !user.getName().startsWith("anonymous-");
    }
}
