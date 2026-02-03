package com.wherestrangersmeet.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class FirebaseConfig {
    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @PostConstruct
    public void initialize() throws IOException {
        log.info("========== INITIALIZING FIREBASE ==========");

        if (FirebaseApp.getApps().isEmpty()) {
            InputStream serviceAccount;
            String envCredentials = System.getenv("FIREBASE_CREDENTIALS");

            if (envCredentials != null && !envCredentials.isEmpty()) {
                log.info("Using Firebase credentials from environment variable");
                serviceAccount = new java.io.ByteArrayInputStream(envCredentials.getBytes());
            } else {
                log.info("Using Firebase credentials from firebase-service-account.json");
                try {
                    serviceAccount = new ClassPathResource("firebase-service-account.json").getInputStream();
                } catch (IOException e) {
                    log.error("ERROR: Cannot find firebase-service-account.json!");
                    log.error("Make sure the file exists in src/main/resources/");
                    throw e;
                }
            }

            try {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);
                log.info("✓ Firebase initialized successfully!");
            } catch (Exception e) {
                log.error("✗ FIREBASE INITIALIZATION FAILED!");
                log.error("Error: {}", e.getMessage());
                throw e;
            }
        } else {
            log.info("Firebase already initialized");
        }

        log.info("===========================================");
    }

    @Bean
    public FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }
}
