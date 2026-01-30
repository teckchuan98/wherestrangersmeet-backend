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
    private final FileStorageService fileStorageService;

    @Transactional
    public Message sendMessage(Long senderId, Long receiverId, String text) {
        Message message = Message.builder()
                .senderId(senderId)
                .receiverId(receiverId)
                .text(text)
                .isRead(false)
                .build();
        return messageRepository.save(message);
    }

    public List<Message> getConversation(Long userId1, Long userId2) {
        return messageRepository.findConversation(userId1, userId2);
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
