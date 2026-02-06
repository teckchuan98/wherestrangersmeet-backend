package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.SelfieExchange;
import com.wherestrangersmeet.backend.repository.SelfieExchangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SelfieExchangeService {

    private final SelfieExchangeRepository selfieExchangeRepository;
    private final MessageService messageService;
    private final UserCache userCache;
    private final SimpMessagingTemplate messagingTemplate;
    private final FileStorageService fileStorageService;

    @Transactional
    public Map<String, Object> createRequest(Long requesterId, Long receiverId) {
        if (requesterId.equals(receiverId)) {
            throw new IllegalArgumentException("Cannot create selfie request to yourself");
        }

        SelfieExchange exchange = SelfieExchange.builder()
                .requesterId(requesterId)
                .receiverId(receiverId)
                .status(SelfieExchange.Status.REQUESTED)
                .build();
        SelfieExchange saved = selfieExchangeRepository.save(exchange);

        messageService.sendMessage(
                requesterId,
                receiverId,
                "SELFIE_REQUEST:" + saved.getId(),
                "SELFIE_REQUEST",
                null,
                null,
                null,
                true
        );

        broadcastUpdate(saved);
        return toDto(saved, requesterId);
    }

    @Transactional
    public Map<String, Object> acceptRequest(Long exchangeId, Long currentUserId) {
        SelfieExchange exchange = selfieExchangeRepository.findById(exchangeId)
                .orElseThrow(() -> new IllegalArgumentException("Selfie request not found"));

        if (!exchange.getReceiverId().equals(currentUserId)) {
            throw new IllegalArgumentException("Only the receiver can accept this request");
        }
        if (exchange.getStatus() == SelfieExchange.Status.CANCELLED) {
            throw new IllegalStateException("Selfie request was cancelled");
        }
        if (exchange.getStatus() == SelfieExchange.Status.COMPLETED) {
            return toDto(exchange, currentUserId);
        }

        exchange.setStatus(SelfieExchange.Status.ACTIVE);
        SelfieExchange saved = selfieExchangeRepository.save(exchange);
        broadcastUpdate(saved);
        return toDto(saved, currentUserId);
    }

    @Transactional
    public Map<String, Object> uploadSelfie(Long exchangeId, Long currentUserId, String attachmentKey, String attachmentHash) {
        SelfieExchange exchange = selfieExchangeRepository.findById(exchangeId)
                .orElseThrow(() -> new IllegalArgumentException("Selfie request not found"));

        if (!isParticipant(exchange, currentUserId)) {
            throw new IllegalArgumentException("You are not a participant of this selfie exchange");
        }
        if (exchange.getStatus() == SelfieExchange.Status.CANCELLED) {
            throw new IllegalStateException("Selfie request was cancelled");
        }
        if (exchange.getStatus() == SelfieExchange.Status.COMPLETED) {
            return toDto(exchange, currentUserId);
        }

        if (exchange.getRequesterId().equals(currentUserId)) {
            exchange.setRequesterPhotoKey(attachmentKey);
            exchange.setRequesterPhotoHash(attachmentHash);
        } else {
            exchange.setReceiverPhotoKey(attachmentKey);
            exchange.setReceiverPhotoHash(attachmentHash);
        }

        boolean requesterReady = exchange.getRequesterPhotoKey() != null && !exchange.getRequesterPhotoKey().isBlank();
        boolean receiverReady = exchange.getReceiverPhotoKey() != null && !exchange.getReceiverPhotoKey().isBlank();

        if (requesterReady && receiverReady && exchange.getStatus() != SelfieExchange.Status.COMPLETED) {
            exchange.setStatus(SelfieExchange.Status.COMPLETED);
            SelfieExchange saved = selfieExchangeRepository.save(exchange);

            // Reveal only when both submitted: now publish both as chat images.
            messageService.sendMessage(
                    saved.getRequesterId(),
                    saved.getReceiverId(),
                    "[Selfie Exchange]",
                    "IMAGE",
                    saved.getRequesterPhotoKey(),
                    null,
                    saved.getRequesterPhotoHash(),
                    true
            );
            messageService.sendMessage(
                    saved.getReceiverId(),
                    saved.getRequesterId(),
                    "[Selfie Exchange]",
                    "IMAGE",
                    saved.getReceiverPhotoKey(),
                    null,
                    saved.getReceiverPhotoHash(),
                    true
            );

            broadcastUpdate(saved);
            return toDto(saved, currentUserId);
        }

        SelfieExchange saved = selfieExchangeRepository.save(exchange);
        broadcastUpdate(saved);
        return toDto(saved, currentUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActiveExchange(Long currentUserId, Long otherUserId) {
        return selfieExchangeRepository
                .findLatestActiveOrRequestedBetween(currentUserId, otherUserId)
                .map(exchange -> toDto(exchange, currentUserId))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getExchangeByIdForUser(Long exchangeId, Long currentUserId) {
        SelfieExchange exchange = selfieExchangeRepository.findById(exchangeId)
                .orElseThrow(() -> new IllegalArgumentException("Selfie request not found"));
        if (!isParticipant(exchange, currentUserId)) {
            throw new IllegalArgumentException("You are not a participant of this selfie exchange");
        }
        return toDto(exchange, currentUserId);
    }

    private boolean isParticipant(SelfieExchange exchange, Long userId) {
        return exchange.getRequesterId().equals(userId) || exchange.getReceiverId().equals(userId);
    }

    private void broadcastUpdate(SelfieExchange exchange) {
        String requesterUid = userCache.getFirebaseUid(exchange.getRequesterId());
        String receiverUid = userCache.getFirebaseUid(exchange.getReceiverId());

        Map<String, Object> payloadForRequester = toDto(exchange, exchange.getRequesterId());
        Map<String, Object> payloadForReceiver = toDto(exchange, exchange.getReceiverId());
        payloadForRequester.put("eventType", "SELFIE_EXCHANGE_UPDATE");
        payloadForReceiver.put("eventType", "SELFIE_EXCHANGE_UPDATE");

        messagingTemplate.convertAndSendToUser(requesterUid, "/queue/selfie", payloadForRequester);
        messagingTemplate.convertAndSendToUser(receiverUid, "/queue/selfie", payloadForReceiver);
    }

    private Map<String, Object> toDto(SelfieExchange exchange, Long viewerId) {
        boolean viewerIsRequester = exchange.getRequesterId().equals(viewerId);
        String myPhotoKey = viewerIsRequester ? exchange.getRequesterPhotoKey() : exchange.getReceiverPhotoKey();
        String otherPhotoKey = viewerIsRequester ? exchange.getReceiverPhotoKey() : exchange.getRequesterPhotoKey();

        boolean mySubmitted = myPhotoKey != null && !myPhotoKey.isBlank();
        boolean otherSubmitted = otherPhotoKey != null && !otherPhotoKey.isBlank();
        boolean completed = exchange.getStatus() == SelfieExchange.Status.COMPLETED;

        Map<String, Object> dto = new HashMap<>();
        dto.put("id", exchange.getId());
        dto.put("requesterId", exchange.getRequesterId());
        dto.put("receiverId", exchange.getReceiverId());
        dto.put("status", exchange.getStatus().name());
        dto.put("mySubmitted", mySubmitted);
        dto.put("otherSubmitted", otherSubmitted);
        dto.put("createdAt", exchange.getCreatedAt());
        dto.put("updatedAt", exchange.getUpdatedAt());

        if (mySubmitted) {
            dto.put("myPhotoUrl", fileStorageService.generatePresignedUrl(myPhotoKey));
        }
        if (completed && otherSubmitted) {
            dto.put("otherPhotoUrl", fileStorageService.generatePresignedUrl(otherPhotoKey));
        }
        return dto;
    }
}
