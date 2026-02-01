package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.MessageRepository;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final com.wherestrangersmeet.backend.service.FileStorageService fileStorageService;
    private final MediaFileService mediaFileService;
    private final NotificationService notificationService;
    private final org.springframework.messaging.simp.SimpMessagingTemplate simpMessagingTemplate;

    @Transactional
    public Message sendMessage(Long senderId, Long receiverId, String text, String messageType, String attachmentUrl,
            Long replyToId, String attachmentHash) {
        return sendMessage(senderId, receiverId, text, messageType, attachmentUrl, replyToId, attachmentHash, true);
    }

    public Message sendMessage(Long senderId, Long receiverId, String text, String messageType, String attachmentUrl,
            Long replyToId, String attachmentHash, boolean broadcast) {
        final String rawAttachmentUrl = attachmentUrl;
        Message message = Message.builder()
                .senderId(senderId)
                .receiverId(receiverId)
                .text(text)
                .messageType(messageType)
                .attachmentUrl(attachmentUrl)
                .replyToId(replyToId)
                .isRead(false)
                .createdAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore")))
                .build();
        Message savedMessage = messageRepository.save(message);

        if (attachmentHash != null && rawAttachmentUrl != null && !rawAttachmentUrl.startsWith("http")) {
            mediaFileService.recordIfAbsent(attachmentHash, rawAttachmentUrl);
        }

        // Presign for immediate display (Critical for real-time WebSocket)
        if (savedMessage.getAttachmentUrl() != null) {
            String originalUrl = savedMessage.getAttachmentUrl();
            String presigned = fileStorageService.generatePresignedUrl(originalUrl);
            System.out.println("🔄 [MessageService] Presigning URL for msg " + savedMessage.getId() + ": " + originalUrl
                    + " -> " + presigned);
            savedMessage.setAttachmentUrl(presigned);
        }

        // Send Push Notification & WebSocket Update
        userRepository.findById(receiverId).ifPresent(receiver -> {
            // WebSocket Push (Fastest)
            if (broadcast && receiver.getFirebaseUid() != null) {
                simpMessagingTemplate.convertAndSendToUser(
                        receiver.getFirebaseUid(),
                        "/queue/messages",
                        savedMessage);
            }

            // Firebase Push Notification (Async, persistent)
            if (receiver.getFcmToken() != null && !receiver.getFcmToken().isEmpty()) {
                userRepository.findById(senderId).ifPresent(sender -> {
                    String title = sender.getName();
                    String body = "TEXT".equals(messageType) ? text : "Sent a " + messageType.toLowerCase();

                    java.util.Map<String, String> data = new java.util.HashMap<>();
                    data.put("type", "CHAT");
                    data.put("senderId", String.valueOf(senderId));
                    data.put("senderName", sender.getName());

                    notificationService.sendNotification(receiver.getFcmToken(), title, body, data);
                });
            }
        });

        // (Presigned URL already set above)

        return savedMessage;
    }

    public List<Message> getConversation(Long userId1, Long userId2, int page, int size) {
        return getConversation(userId1, userId2, size, null, null);
    }

    public List<Message> getConversation(Long userId1, Long userId2, int size,
            java.time.LocalDateTime beforeCreatedAt, Long beforeId) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, size);
        List<Message> messages;
        if (beforeCreatedAt != null && beforeId != null) {
            messages = messageRepository.findConversationBefore(userId1, userId2, beforeCreatedAt, beforeId, pageable);
        } else {
            messages = messageRepository.findConversation(userId1, userId2, pageable);
        }

        // Parallel presigned URL generation for better performance
        messages.parallelStream().forEach(m -> {
            if (m.getAttachmentUrl() != null) {
                m.setAttachmentUrl(fileStorageService.generatePresignedUrl(m.getAttachmentUrl()));
            }
        });

        // Backend query is DESC (newest first).
        // We now return it as-is (DESC) so frontend can use reverse ListView (Newest at
        // index 0).
        // Collections.reverse(messages); // REMOVED

        return messages;
    }

    public List<Map<String, Object>> getConversations(Long userId) {
        List<Message> allMessages = messageRepository.findByUserId(userId);

        // Map to store latest message per partner
        Map<Long, Message> latestMessages = new HashMap<>();

        for (Message m : allMessages) {
            Long partnerId = m.getSenderId().equals(userId) ? m.getReceiverId() : m.getSenderId();
            // Since list is ordered by DESC, first encounter is the latest
            latestMessages.putIfAbsent(partnerId, m);
        }

        List<Map<String, Object>> conversations = new ArrayList<>();

        for (Map.Entry<Long, Message> entry : latestMessages.entrySet()) {
            Long partnerId = entry.getKey();
            Message latest = entry.getValue();

            Optional<User> partnerOpt = userRepository.findById(partnerId);
            if (partnerOpt.isPresent()) {
                User partner = partnerOpt.get();
                // Process Avatar URL
                if (partner.getAvatarUrl() != null) {
                    partner.setAvatarUrl(fileStorageService.generatePresignedUrl(partner.getAvatarUrl()));
                }

                // Process Photos in parallel for better performance
                if (partner.getPhotos() != null) {
                    partner.getPhotos().parallelStream().forEach(photo -> {
                        if (photo.getUrl() != null) {
                            photo.setUrl(fileStorageService.generatePresignedUrl(photo.getUrl()));
                        }
                    });
                }

                Map<String, Object> conv = new HashMap<>();
                conv.put("partner", partner);
                conv.put("lastMessage", latest);
                conversations.add(conv);
            }
        }

        // Sort by last message time
        conversations.sort((a, b) -> {
            Message m1 = (Message) a.get("lastMessage");
            Message m2 = (Message) b.get("lastMessage");
            return m2.getCreatedAt().compareTo(m1.getCreatedAt());
        });

        return conversations;
    }

    @Transactional
    public void markAsRead(Long receiverId, Long senderId) {
        List<Message> unreadMessages = messageRepository.findBySenderIdAndReceiverIdAndIsReadFalse(senderId,
                receiverId);
        if (!unreadMessages.isEmpty()) {
            unreadMessages.forEach(m -> m.setIsRead(true));
            List<Message> savedMessages = messageRepository.saveAll(unreadMessages);

            // Send WebSocket update to SENDER for each read message (blue tick update)
            userRepository.findById(senderId).ifPresent(sender -> {
                if (sender.getFirebaseUid() != null) {
                    savedMessages.forEach(message -> {
                        // Presign attachment URL if needed
                        if (message.getAttachmentUrl() != null) {
                            message.setAttachmentUrl(
                                    fileStorageService.generatePresignedUrl(message.getAttachmentUrl()));
                        }

                        simpMessagingTemplate.convertAndSendToUser(
                                sender.getFirebaseUid(),
                                "/queue/messages",
                                message);
                    });
                }
            });
        }
    }

    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("You can only delete your own messages");
        }

        message.setIsDeleted(true);
        messageRepository.save(message);
    }
}
