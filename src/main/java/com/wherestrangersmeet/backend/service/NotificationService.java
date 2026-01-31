package com.wherestrangersmeet.backend.service;

import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationService {

    public void sendNotification(String token, String title, String body, Map<String, String> data) {
        if (token == null || token.isEmpty()) {
            System.out.println("⚠️ Notification skipped: Token is null or empty");
            return;
        }

        // Log token (masked) and payload
        String maskedToken = token.length() > 10 ? token.substring(0, 5) + "..." + token.substring(token.length() - 5)
                : "SHORT_TOKEN";
        System.out.println("\n🚀 PREPARING TO SEND NOTIFICATION 🚀");
        System.out.println("Target Token: " + maskedToken);
        System.out.println("Title: " + title);
        System.out.println("Body: " + body);
        System.out.println("Data: " + data);

        try {
            // Android Config
            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder()
                            .setSound("default")
                            .setClickAction("FLUTTER_NOTIFICATION_CLICK") // Standard for Flutter
                            .build())
                    .build();

            // APNs (iOS) Config
            ApnsConfig apnsConfig = ApnsConfig.builder()
                    .setAps(Aps.builder()
                            .setAlert(ApsAlert.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .build())
                            .setSound("default")
                            .setCategory("CHAT_MESSAGE")
                            .setContentAvailable(true) // Important for background updates
                            .build())
                    .putHeader("apns-priority", "10") // High priority
                    .build();

            Notification notification = Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build();

            Message.Builder messageBuilder = Message.builder()
                    .setToken(token)
                    .setNotification(notification)
                    .setAndroidConfig(androidConfig)
                    .setApnsConfig(apnsConfig);

            if (data != null) {
                messageBuilder.putAllData(data);
            }

            Message message = messageBuilder.build();
            System.out.println("Sending to Firebase...");

            String response = FirebaseMessaging.getInstance().send(message);
            System.out.println("✅ FIREBASE RESPONSE: " + response);
            System.out.println("Notification sent successfully.\n");
        } catch (Exception e) {
            System.err.println("❌ FAILED TO SEND NOTIFICATION ❌");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(); // Print full stack trace for debugging
        }
    }
}
