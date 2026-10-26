package com.aloute.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;
    private final AccountWebSocketGuard guard;

    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    @Value("${app.websocket.broker-relay.enabled:false}")
    private boolean brokerRelayEnabled;

    @Value("${app.websocket.broker-relay.host:localhost}")
    private String brokerRelayHost;

    @Value("${app.websocket.broker-relay.port:61613}")
    private int brokerRelayPort;

    @Value("${app.websocket.broker-relay.username:}")
    private String brokerRelayUsername;

    @Value("${app.websocket.broker-relay.password:}")
    private String brokerRelayPassword;

    @Value("${app.websocket.broker-relay.virtual-host:/}")
    private String brokerRelayVirtualHost;

    public WebSocketConfig(JwtUtil jwtUtil, AccountWebSocketGuard guard) {
        this.guard = guard;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = AllowedOriginPolicy.parse(allowedOrigins).toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .addInterceptors(new JwtHandshakeInterceptor(jwtUtil))
                .setHandshakeHandler(new AlouteHandshakeHandler())
                .withSockJS().setClientLibraryUrl("/vendor/sockjs-client/sockjs.min.js");
    }

    @Override
    public void configureClientInboundChannel(
            org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(guard);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        if (brokerRelayEnabled) {
            if (brokerRelayHost == null || brokerRelayHost.isBlank()
                    || brokerRelayPort < 1 || brokerRelayPort > 65535
                    || brokerRelayUsername == null || brokerRelayUsername.isBlank()
                    || brokerRelayPassword == null || brokerRelayPassword.isBlank()) {
                throw new IllegalStateException(
                        "WebSocket broker relay requires a valid host, port, username and password");
            }
            // Shared STOMP broker: allows messages/user destinations to be routed across app replicas.
            registry.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(brokerRelayHost)
                    .setRelayPort(brokerRelayPort)
                    .setClientLogin(brokerRelayUsername)
                    .setClientPasscode(brokerRelayPassword)
                    .setSystemLogin(brokerRelayUsername)
                    .setSystemPasscode(brokerRelayPassword)
                    .setVirtualHost(brokerRelayVirtualHost)
                    // Broadcast session registries and unresolved /user destinations across app nodes.
                    .setUserRegistryBroadcast("/topic/aloute-simp-user-registry")
                    .setUserDestinationBroadcast("/topic/aloute-unresolved-user-destination");
        } else {
            // Local development only: Spring's SimpleBroker is in-process and single-instance.
            registry.enableSimpleBroker("/topic", "/queue");
        }
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
