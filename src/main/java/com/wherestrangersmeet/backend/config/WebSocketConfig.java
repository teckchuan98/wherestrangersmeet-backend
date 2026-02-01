package com.wherestrangersmeet.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple broker for both topics (public) and queues (private)
        // /topic for general broadcasts
        // /queue for user-specific messages (automatically handled by
        // convertAndSendToUser)
        config.enableSimpleBroker("/topic", "/queue");

        // Application prefixes for filtering messages targeted at application handling
        // methods
        config.setApplicationDestinationPrefixes("/app");

        // Use specific prefix for user destinations
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // Allow all origins for dev; restrict in prod
                .withSockJS(); // Fallback option

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*"); // Pure WebSocket endpoint for Flutter
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
