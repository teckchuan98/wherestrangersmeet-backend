package com.wherestrangersmeet.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;

    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("wss-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple broker for both topics (public) and queues (private)
        // /topic for general broadcasts
        // /queue for user-specific messages (automatically handled by
        // convertAndSendToUser)

        // Add heartbeat: [server-send-interval, client-receive-interval]
        // Server sends heartbeat every 20s, expects client heartbeat every 20s
        // If no heartbeat received for 20s, connection is considered dead
        config.enableSimpleBroker("/topic", "/queue")
              .setHeartbeatValue(new long[] {20000, 20000})
              .setTaskScheduler(heartbeatScheduler());

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

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Set timeouts for faster disconnect detection
        registration
            .setMessageSizeLimit(128 * 1024)     // 128KB max message size
            .setSendBufferSizeLimit(512 * 1024)  // 512KB send buffer
            .setSendTimeLimit(20 * 1000)         // 20s send timeout
            .setTimeToFirstMessage(30 * 1000);   // 30s to receive first message after connect
    }
}
