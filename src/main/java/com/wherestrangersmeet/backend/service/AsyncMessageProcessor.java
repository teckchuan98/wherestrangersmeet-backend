package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AsyncMessageProcessor {

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    @Async
    public void processInBackground(Message message, boolean broadcast) {
        // 1. Presign attachment URL for receiver (if needed)
        // Note: The message object passed here might be detached from Hibernate
        // session, which is fine.
        if (message.getAttachmentUrl() != null && !message.getAttachmentUrl().startsWith("http")) {
            String presigned = fileStorageService.generatePresignedUrl(message.getAttachmentUrl());
            message.setAttachmentUrl(presigned);
        }

        // 2. WebSocket Broadcast (Fastest)
        userRepository.findById(message.getReceiverId()).ifPresent(receiver -> {
            if (broadcast && receiver.getFirebaseUid() != null) {
                simpMessagingTemplate.convertAndSendToUser(
                        receiver.getFirebaseUid(),
                        "/queue/messages",
                        message);
            }

            // 3. Firebase Push Notification (Async, persistent)
            if (receiver.getFcmToken() != null && !receiver.getFcmToken().isEmpty()) {
                userRepository.findById(message.getSenderId()).ifPresent(sender -> {
                    String title = sender.getName();
                    String body = "TEXT".equals(message.getMessageType()) ? message.getText()
                            : "Sent a " + message.getMessageType().toLowerCase();

                    java.util.Map<String, String> data = new java.util.HashMap<>();
                    data.put("type", "CHAT");
                    data.put("senderId", String.valueOf(message.getSenderId()));
                    data.put("senderName", sender.getName());

                    notificationService.sendNotification(receiver.getFcmToken(), title, body, data);
                });
            }
        });
    }
}
