package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.SelfieExchange;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.MessageRepository;
import com.wherestrangersmeet.backend.repository.SelfieExchangeRepository;
import com.wherestrangersmeet.backend.repository.UserReportRepository;
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
    private final MediaFileService mediaFileService;
    private final AsyncMessageProcessor asyncMessageProcessor;
    private final org.springframework.messaging.simp.SimpMessagingTemplate simpMessagingTemplate;
    private final SelfieExchangeRepository selfieExchangeRepository;
    private final UserReportRepository userReportRepository;
    // Note: NotificationService logic moved to AsyncMessageProcessor

    // ORCHESTRATOR: Not Transactional (to avoid long-running DB connections)
    public Message sendMessage(Long senderId, Long receiverId, String text, String messageType, String attachmentUrl,
            Long replyToId, String attachmentHash) {
        return sendMessage(senderId, receiverId, text, messageType, attachmentUrl, replyToId, attachmentHash, true);
    }

    public Message sendMessage(Long senderId, Long receiverId, String text, String messageType, String attachmentUrl,
            Long replyToId, String attachmentHash, boolean broadcast) {
        ensureNotBlocked(senderId, receiverId);

        // 1. SYNC: Save to DB (Fast, Ordered)
        Message savedMessage = saveMessageToDb(senderId, receiverId, text, messageType, attachmentUrl, replyToId,
                attachmentHash);

        // 2. ASYNC: Fire & Forget delivery (Heavy)
        asyncMessageProcessor.processInBackground(savedMessage, broadcast);

        // 3. Return immediately (Prescription for UI: Use what we have, URL might be
        // raw but that's fine for sender if local)
        // If sender needs presigned URL immediately, we can generate it here quickly OR
        // rely on their local file path.
        // For optimisations, we return the saved entity.
        if (savedMessage.getAttachmentUrl() != null && !savedMessage.getAttachmentUrl().startsWith("http")) {
            // Quick presign for the sender's response (using cache hopefully)
            savedMessage.setAttachmentUrl(fileStorageService.generatePresignedUrl(savedMessage.getAttachmentUrl()));
        }

        return savedMessage;
    }

    @Transactional
    public Message saveMessageToDb(Long senderId, Long receiverId, String text, String messageType,
            String attachmentUrl,
            Long replyToId, String attachmentHash) {
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

        return savedMessage;
    }

    public List<Message> getConversation(Long userId1, Long userId2, int page, int size) {
        return getConversation(userId1, userId2, size, null, null);
    }

    public List<Message> getConversation(Long userId1, Long userId2, int size,
            java.time.LocalDateTime beforeCreatedAt, Long beforeId) {
        if (isUserPairBlocked(userId1, userId2)) {
            return Collections.emptyList();
        }

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, size);
        List<Message> messages;
        if (beforeCreatedAt != null && beforeId != null) {
            messages = messageRepository.findConversationBefore(userId1, userId2, beforeCreatedAt, beforeId, pageable);
        } else {
            messages = messageRepository.findConversation(userId1, userId2, pageable);
        }

        // Parallel presigned URL generation for better performance
        // Now much faster due to @Cacheable in FileStorageService
        messages.parallelStream().forEach(m -> {
            if (m.getAttachmentUrl() != null) {
                m.setAttachmentUrl(fileStorageService.generatePresignedUrl(m.getAttachmentUrl()));
            }
        });

        return messages;
    }

    public List<Map<String, Object>> getConversations(Long userId) {
        List<Message> allMessages = messageRepository.findByUserId(userId);
        Set<Long> blockedPartnerIds = new HashSet<>();
        blockedPartnerIds.addAll(userReportRepository.findReportedUserIdsByReporterUserId(userId));
        blockedPartnerIds.addAll(userReportRepository.findReporterUserIdsByReportedUserId(userId));

        // Map to store latest message per partner
        Map<Long, Message> latestMessages = new HashMap<>();

        for (Message m : allMessages) {
            Long partnerId = m.getSenderId().equals(userId) ? m.getReceiverId() : m.getSenderId();
            if (blockedPartnerIds.contains(partnerId)) {
                continue;
            }
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

        // Locked rule: completed selfie request messages cannot be deleted.
        if ("SELFIE_REQUEST".equals(message.getMessageType())) {
            Long exchangeId = extractSelfieExchangeId(message.getText());
            if (exchangeId != null) {
                selfieExchangeRepository.findById(exchangeId).ifPresent(exchange -> {
                    if (exchange.getStatus() == SelfieExchange.Status.COMPLETED) {
                        throw new IllegalStateException("Completed selfie request messages cannot be deleted");
                    }
                });
            }
        }

        message.setIsDeleted(true);
        messageRepository.save(message);
    }

    private Long extractSelfieExchangeId(String text) {
        if (text == null) return null;
        String prefix = "SELFIE_REQUEST:";
        if (!text.startsWith(prefix)) return null;
        try {
            return Long.parseLong(text.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void ensureNotBlocked(Long senderId, Long receiverId) {
        if (isUserPairBlocked(senderId, receiverId)) {
            throw new IllegalStateException("Cannot message a blocked user");
        }
    }

    private boolean isUserPairBlocked(Long userId1, Long userId2) {
        return userReportRepository.existsReportBetweenUsers(userId1, userId2);
    }
}
