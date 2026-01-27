package com.wherestrangersmeet.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class FirebaseConfig {

    @PostConstruct
    public void initialize() throws IOException {
        System.out.println("========== INITIALIZING FIREBASE ==========");

        if (FirebaseApp.getApps().isEmpty()) {
            InputStream serviceAccount;
            String envCredentials = System.getenv("FIREBASE_CREDENTIALS");

            if (envCredentials != null && !envCredentials.isEmpty()) {
                System.out.println("Using Firebase credentials from environment variable");
                serviceAccount = new java.io.ByteArrayInputStream(envCredentials.getBytes());
            } else {
                System.out.println("Using Firebase credentials from firebase-service-account.json");
                try {
                    serviceAccount = new ClassPathResource("firebase-service-account.json").getInputStream();
                } catch (IOException e) {
                    System.err.println("ERROR: Cannot find firebase-service-account.json!");
                    System.err.println("Make sure the file exists in src/main/resources/");
                    throw e;
                }
            }

            try {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);
                System.out.println("✓ Firebase initialized successfully!");
            } catch (Exception e) {
                System.err.println("✗ FIREBASE INITIALIZATION FAILED!");
                System.err.println("Error: " + e.getMessage());
                throw e;
            }
        } else {
            System.out.println("Firebase already initialized");
        }

        System.out.println("===========================================");
    }

    @Bean
    public FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }
}
