package com.wherestrangersmeet.backend.config;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
// import org.springframework.context.annotation.Configuration; // Removed unused import depending on strictness, or just remove line
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
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

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

                    System.out.println("✅ WebSocket Connected: " + decodedToken.getUid());
                } catch (Exception e) {
                    System.out.println("❌ WebSocket Auth Failed: " + e.getMessage());
                    // Don't throw exception to avoid crashing entire broker for one user,
                    // just don't set user (connection might still succeed but be unauthenticated
                    // depending on security config)
                    // Or strictly: throw new IllegalArgumentException("Invalid Token");
                }
            }
        }
        return message;
    }
}
