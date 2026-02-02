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
    private final com.wherestrangersmeet.backend.repository.MessageRepository messageRepository;
    private final AiService aiService;
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

                    // If it's an AI message, customize notice
                    if (message.getMessageType().startsWith("AI_")) {
                        title = "WSM AI";
                        body = message.getText();
                    }

                    java.util.Map<String, String> data = new java.util.HashMap<>();
                    data.put("type", "CHAT");
                    data.put("senderId", String.valueOf(message.getSenderId()));
                    data.put("senderName", sender.getName());

                    notificationService.sendNotification(receiver.getFcmToken(), title, body, data);
                });
            }
        });

        // 4. AI Trigger Detection (@ai)
        // Only process if TEXT message and contains @ai (case-insensitive)
        if ("TEXT".equals(message.getMessageType()) && message.getText() != null &&
                message.getText().toLowerCase().contains("@momo")) {

            // Prevent infinite loops if AI somehow says @ai (unlikely but safe)
            if (message.getMessageType().startsWith("AI_"))
                return;

            handleAiTrigger(message);
        }
    }

    private void handleAiTrigger(Message originalMessage) {
        try {
            // 0. Notify "Thinking" state (WebSocket ONLY, do not save to DB)
            Message thinkingMsg = Message.builder()
                    .senderId(originalMessage.getSenderId()) // Ghost sender
                    .receiverId(originalMessage.getReceiverId())
                    .text("MOMO AI is processing...")
                    .messageType("AI_PROCESSING")
                    .createdAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore")))
                    .build();

            // Broadcast to Receiver
            userRepository.findById(originalMessage.getReceiverId()).ifPresent(receiver -> {
                if (receiver.getFirebaseUid() != null) {
                    simpMessagingTemplate.convertAndSendToUser(receiver.getFirebaseUid(), "/queue/messages",
                            thinkingMsg);
                }
            });
            // Broadcast to Sender
            userRepository.findById(originalMessage.getSenderId()).ifPresent(sender -> {
                if (sender.getFirebaseUid() != null) {
                    simpMessagingTemplate.convertAndSendToUser(sender.getFirebaseUid(), "/queue/messages", thinkingMsg);
                }
            });

            // Fetch Conversation History
            // We need the last ~30 messages to give context
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 30);
            java.util.List<Message> history = messageRepository.findConversation(
                    originalMessage.getSenderId(),
                    originalMessage.getReceiverId(),
                    pageable);

            // Generate AI Response
            // 5. Call AI Service
            AiService.AiResponse aiResponse = aiService.generateResponse(history, originalMessage);

            // Create and Save AI Message
            // We "ghost" the sender for now so it appears in the conversation query
            // But the messageType will distinguish it in UI
            Message aiMessage = Message.builder()
                    .senderId(originalMessage.getSenderId()) // Same sender for query compatibility
                    .receiverId(originalMessage.getReceiverId())
                    .text(aiResponse.text())
                    .messageType(aiResponse.type()) // AI_SERIOUS or AI_JOKER
                    .createdAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore")))
                    .isRead(false)
                    .build();

            Message savedAiMessage = messageRepository.save(aiMessage);

            // Recursively process this new message to Broadcast & Notify (to Receiver)
            processInBackground(savedAiMessage, true);

            // CRITICAL: Also broadcast to the SENDER (the triggers) via WebSocket
            // Because they didn't "send" this text locally, they need to receive it from
            // server
            userRepository.findById(originalMessage.getSenderId()).ifPresent(sender -> {
                if (sender.getFirebaseUid() != null) {
                    simpMessagingTemplate.convertAndSendToUser(
                            sender.getFirebaseUid(),
                            "/queue/messages",
                            savedAiMessage);
                }
            });

        } catch (Exception e) {
            System.err.println("Error processing AI trigger: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
