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
        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        System.out.println("========== FIREBASE AUTH FILTER ==========");
        System.out.println("Request: " + method + " " + requestUri);
        System.out.println("Authorization header present: " + (authHeader != null));

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String idToken = authHeader.substring(7);
            System.out.println("Token found (length: " + idToken.length() + ")");
            System.out.println("Token preview: " + idToken.substring(0, Math.min(50, idToken.length())) + "...");

            try {
                System.out.println("Attempting to verify token with Firebase...");
                FirebaseToken decodedToken = firebaseAuth.verifyIdToken(idToken);
                String uid = decodedToken.getUid();
                String email = decodedToken.getEmail();

                System.out.println("✓ Token verified successfully!");
                System.out.println("  UID: " + uid);
                System.out.println("  Email: " + email);
                System.out.println("  Name: " + decodedToken.getName());

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        decodedToken,
                        null,
                        Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);

                System.out.println("✓ SecurityContext set for user: " + uid);

            } catch (Exception e) {
                System.err.println("✗ FIREBASE TOKEN VERIFICATION FAILED!");
                System.err.println("  Error type: " + e.getClass().getName());
                System.err.println("  Error message: " + e.getMessage());
                System.err.println("  This will result in 403 Forbidden for authenticated endpoints");
                logger.error("Firebase token verification failed", e);
                e.printStackTrace();
            }
        } else {
            System.out.println("No Bearer token found in Authorization header");
            if (authHeader != null) {
                System.out.println("Authorization header value: " + authHeader.substring(0, Math.min(20, authHeader.length())) + "...");
            }
        }

        System.out.println("==========================================");
        filterChain.doFilter(request, response);
    }
}
