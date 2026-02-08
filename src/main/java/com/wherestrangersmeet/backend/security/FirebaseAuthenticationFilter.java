package com.wherestrangersmeet.backend.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);

    private final FirebaseAuth firebaseAuth;

    public FirebaseAuthenticationFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String idToken = authHeader.substring(7).trim();

            if (idToken.isEmpty() || "null".equalsIgnoreCase(idToken) || "undefined".equalsIgnoreCase(idToken)) {
                filterChain.doFilter(request, response);
                return;
            }

            try {
                FirebaseToken decodedToken = firebaseAuth.verifyIdToken(idToken);

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        decodedToken,
                        null,
                        Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (Exception e) {
                logger.warn("Firebase token verification failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
