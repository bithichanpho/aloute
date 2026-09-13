package com.aloute.config;

import java.security.Principal;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.aloute.service.PresenceService;

@Component
public class WebSocketEventListener {

    private final PresenceService presenceService;

    public WebSocketEventListener(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @EventListener
    public void handleConnected(SessionConnectedEvent event) {
        Principal user = event.getUser();
        if (isRealUser(user)) {
            presenceService.userConnected(user.getName());
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (isRealUser(user)) {
            presenceService.userDisconnected(user.getName());
        }
    }

    private boolean isRealUser(Principal user) {
        return user != null && user.getName() != null && !user.getName().startsWith("anonymous-");
    }
}