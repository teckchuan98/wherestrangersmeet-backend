package com.wherestrangersmeet.backend.service;

import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

import org.springframework.scheduling.annotation.Async;

@Service
@RequiredArgsConstructor
public class NotificationService {

        private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

        @Async
        public void sendNotification(String token, String title, String body, Map<String, String> data) {
                if (token == null || token.isEmpty()) {
                        log.warn("⚠️ Notification skipped: Token is null or empty");
                        return;
                }

                // Log token (masked) and payload
                String maskedToken = token.length() > 10
                                ? token.substring(0, 5) + "..." + token.substring(token.length() - 5)
                                : "SHORT_TOKEN";
                log.info("🚀 PREPARING TO SEND NOTIFICATION 🚀");
                log.info("Target Token: {}", maskedToken);
                log.info("Title: {}", title);
                log.info("Body: {}", body);
                log.info("Data: {}", data);

                try {
                        // Android Config
                        AndroidConfig androidConfig = AndroidConfig.builder()
                                        .setPriority(AndroidConfig.Priority.HIGH)
                                        .setNotification(AndroidNotification.builder()
                                                        .setSound("default")
                                                        .setClickAction("FLUTTER_NOTIFICATION_CLICK") // Standard for
                                                                                                      // Flutter
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
                        log.info("Sending to Firebase...");

                        String response = FirebaseMessaging.getInstance().send(message);
                        log.info("✅ FIREBASE RESPONSE: {}", response);
                        log.info("Notification sent successfully.");
                } catch (Exception e) {
                        log.error("❌ FAILED TO SEND NOTIFICATION ❌", e);
                }
        }
}
