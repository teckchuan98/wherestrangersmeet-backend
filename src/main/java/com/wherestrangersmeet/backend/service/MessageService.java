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
    private final NotificationService notificationService;

    @Transactional
    public Message sendMessage(Long senderId, Long receiverId, String text, String messageType, String attachmentUrl,
            Long replyToId) {
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

        // Send Push Notification
        userRepository.findById(receiverId).ifPresent(receiver -> {
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

        // Presign for immediate display
        if (savedMessage.getAttachmentUrl() != null && !savedMessage.getAttachmentUrl().startsWith("http")) {
            // We create a copy or modify the return object (it's persistent but we don't
            // save again)
            // Ideally we shouldn't modify the entity if it triggers an update, but we are
            // returning it.
            // To be safe, we can just modify the field on this instance as the transaction
            // ends.
            savedMessage.setAttachmentUrl(fileStorageService.generatePresignedUrl(savedMessage.getAttachmentUrl()));
        }

        return savedMessage;
    }

    public List<Message> getConversation(Long userId1, Long userId2, int page, int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        List<Message> messages = messageRepository.findConversation(userId1, userId2, pageable);

        // Signed URL generation
        messages.forEach(m -> {
            if (m.getAttachmentUrl() != null && !m.getAttachmentUrl().startsWith("http")) {
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
                if (partner.getAvatarUrl() != null && !partner.getAvatarUrl().startsWith("http")) {
                    partner.setAvatarUrl(fileStorageService.generatePresignedUrl(partner.getAvatarUrl()));
                }

                // Process Photos
                if (partner.getPhotos() != null) {
                    partner.getPhotos().forEach(photo -> {
                        if (photo.getUrl() != null && !photo.getUrl().startsWith("http")) {
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
            messageRepository.saveAll(unreadMessages);
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
