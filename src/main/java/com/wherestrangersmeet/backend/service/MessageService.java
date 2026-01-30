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

    public List<Message> getConversation(Long userId1, Long userId2) {
        List<Message> messages = messageRepository.findConversation(userId1, userId2);
        messages.forEach(m -> {
            if (m.getAttachmentUrl() != null && !m.getAttachmentUrl().startsWith("http")) {
                m.setAttachmentUrl(fileStorageService.generatePresignedUrl(m.getAttachmentUrl()));
            }
        });
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
}
