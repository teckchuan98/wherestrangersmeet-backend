package com.wherestrangersmeet.backend.config;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.service.UserService;
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

import java.util.Collections;
import java.util.List;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final UserService userService;

    // Use @Lazy to break circular dependency
    public WebSocketAuthInterceptor(@Lazy UserService userService) {
        this.userService = userService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

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
                        userService.updateUserStatus(user.getId(), true);
                        System.out.println("✅ WebSocket Connected: " + decodedToken.getUid() + " - User marked ONLINE");
                    });

                } catch (Exception e) {
                    System.out.println("❌ WebSocket Auth Failed: " + e.getMessage());
                }
            }
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            // Mark user as OFFLINE when WebSocket disconnects
            if (accessor.getUser() != null) {
                String firebaseUid = accessor.getUser().getName();
                userService.getUserByFirebaseUid(firebaseUid).ifPresent(user -> {
                    userService.updateUserStatus(user.getId(), false);
                    System.out.println("🔴 WebSocket Disconnected: " + firebaseUid + " - User marked OFFLINE");
                });
            }
        }
        return message;
    }
}
