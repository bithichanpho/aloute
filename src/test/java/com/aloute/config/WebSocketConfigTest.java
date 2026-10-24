package com.aloute.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.StompBrokerRelayRegistration;
import org.springframework.test.util.ReflectionTestUtils;

class WebSocketConfigTest {
    @Test
    void relayBranchConfiguresSharedBrokerAndUserDestinationBroadcast() {
        WebSocketConfig config = new WebSocketConfig(mock(JwtUtil.class), mock(AccountWebSocketGuard.class));
        ReflectionTestUtils.setField(config, "brokerRelayEnabled", true);
        ReflectionTestUtils.setField(config, "brokerRelayHost", "rabbitmq");
        ReflectionTestUtils.setField(config, "brokerRelayPort", 61613);
        ReflectionTestUtils.setField(config, "brokerRelayUsername", "aloute");
        ReflectionTestUtils.setField(config, "brokerRelayPassword", "strong-test-password");
        ReflectionTestUtils.setField(config, "brokerRelayVirtualHost", "/");

        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        StompBrokerRelayRegistration relay = mock(StompBrokerRelayRegistration.class);
        when(registry.enableStompBrokerRelay("/topic", "/queue")).thenReturn(relay);
        when(relay.setRelayHost("rabbitmq")).thenReturn(relay);
        when(relay.setRelayPort(61613)).thenReturn(relay);
        when(relay.setClientLogin("aloute")).thenReturn(relay);
        when(relay.setClientPasscode("strong-test-password")).thenReturn(relay);
        when(relay.setSystemLogin("aloute")).thenReturn(relay);
        when(relay.setSystemPasscode("strong-test-password")).thenReturn(relay);
        when(relay.setVirtualHost("/")).thenReturn(relay);
        when(relay.setUserRegistryBroadcast("/topic/aloute-simp-user-registry")).thenReturn(relay);
        when(relay.setUserDestinationBroadcast("/topic/aloute-unresolved-user-destination")).thenReturn(relay);

        config.configureMessageBroker(registry);

        verify(registry).enableStompBrokerRelay("/topic", "/queue");
        verify(relay).setUserRegistryBroadcast("/topic/aloute-simp-user-registry");
        verify(relay).setUserDestinationBroadcast("/topic/aloute-unresolved-user-destination");
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry).setUserDestinationPrefix("/user");
        verify(registry, never()).enableSimpleBroker(any(String[].class));
    }

    @Test
    void relayBranchRefusesMissingCredentials() {
        WebSocketConfig config = new WebSocketConfig(mock(JwtUtil.class), mock(AccountWebSocketGuard.class));
        ReflectionTestUtils.setField(config, "brokerRelayEnabled", true);
        ReflectionTestUtils.setField(config, "brokerRelayHost", "rabbitmq");
        ReflectionTestUtils.setField(config, "brokerRelayPort", 61613);
        ReflectionTestUtils.setField(config, "brokerRelayUsername", "aloute");
        ReflectionTestUtils.setField(config, "brokerRelayPassword", "");
        assertThrows(IllegalStateException.class, () -> config.configureMessageBroker(mock(MessageBrokerRegistry.class)));
    }
}
