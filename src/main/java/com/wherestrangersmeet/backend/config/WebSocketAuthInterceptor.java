package com.wherestrangersmeet.backend.config;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);
    private final UserService userService;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Use @Lazy to break circular dependency
    public WebSocketAuthInterceptor(@Lazy UserService userService) {
        this.userService = userService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        String timestamp = LocalDateTime.now().format(FORMATTER);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            List<String> authorization = accessor.getNativeHeader("Authorization");

            if (authorization != null && !authorization.isEmpty()) {
                String token = authorization.get(0).replace("Bearer ", "");
                try {
                    FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(token);

                    // Create minimal principal object
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            decodedToken.getUid(), null, Collections.emptyList());

                    accessor.setUser(authentication);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // Mark user as ONLINE when WebSocket connects
                    userService.getUserByFirebaseUid(decodedToken.getUid()).ifPresent(user -> {
                        log.info("┌─────────────────────────────────────────────────────");
                        log.info("│ 🔌 WEBSOCKET CONNECT");
                        log.info("│ Time: {}", timestamp);
                        log.info("│ User ID: {}", user.getId());
                        log.info("│ Firebase UID: {}", decodedToken.getUid());
                        log.info("│ Name: {}", user.getName());
                        log.info("│ Session: {}", accessor.getSessionId());
                        log.info("│ Source: WebSocket CONNECT frame");

                        userService.updateUserStatus(user.getId(), true, "WebSocket-CONNECT");

                        log.info("│ Status: ✅ User marked ONLINE");
                        log.info("└─────────────────────────────────────────────────────");
                    });

                } catch (Exception e) {
                    log.warn("┌─────────────────────────────────────────────────────");
                    log.warn("│ ❌ WEBSOCKET AUTH FAILED");
                    log.warn("│ Time: {}", timestamp);
                    log.warn("│ Error: {}", e.getMessage());
                    log.warn("│ Session: {}", accessor.getSessionId());
                    log.warn("└─────────────────────────────────────────────────────");
                }
            }
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            // Mark user as OFFLINE when WebSocket disconnects
            if (accessor.getUser() != null) {
                String firebaseUid = accessor.getUser().getName();
                userService.getUserByFirebaseUid(firebaseUid).ifPresent(user -> {
                    log.info("┌─────────────────────────────────────────────────────");
                    log.info("│ 🔌 WEBSOCKET DISCONNECT");
                    log.info("│ Time: {}", timestamp);
                    log.info("│ User ID: {}", user.getId());
                    log.info("│ Firebase UID: {}", firebaseUid);
                    log.info("│ Name: {}", user.getName());
                    log.info("│ Session: {}", accessor.getSessionId());
                    log.info("│ Source: WebSocket DISCONNECT frame");

                    userService.updateUserStatus(user.getId(), false, "WebSocket-DISCONNECT");

                    log.info("│ Status: 🔴 User marked OFFLINE");
                    log.info("└─────────────────────────────────────────────────────");
                });
            }
        }
        return message;
    }
}
