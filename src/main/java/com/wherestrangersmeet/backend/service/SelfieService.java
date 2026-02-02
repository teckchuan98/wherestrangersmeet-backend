package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.SelfieRequest;
import com.wherestrangersmeet.backend.model.SelfieResponse;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.SelfieRequestRepository;
import com.wherestrangersmeet.backend.repository.SelfieResponseRepository;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class SelfieService {

    private static final int ACCEPT_WINDOW_SECONDS = 30;
    private static final int CAPTURE_WINDOW_SECONDS = 10;
    private static final int CAPTURE_DELAY_SECONDS = 3;

    private final SelfieRequestRepository selfieRequestRepository;
    private final SelfieResponseRepository selfieResponseRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final FileStorageService fileStorageService;
    private final MediaFileService mediaFileService;
    private final SimpMessagingTemplate messagingTemplate;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConcurrentMap<Long, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();

    @Transactional
    public SelfieRequest createRequest(Long requesterId, Long receiverId) {
        LocalDateTime now = LocalDateTime.now();
        SelfieRequest request = SelfieRequest.builder()
                .requesterId(requesterId)
                .receiverId(receiverId)
                .status("PENDING")
                .createdAt(now)
                .expiresAt(now.plusSeconds(ACCEPT_WINDOW_SECONDS))
                .build();
        SelfieRequest saved = selfieRequestRepository.save(request);

        Message requestMessage = messageService.sendSelfieRequestMessage(
                requesterId,
                receiverId,
                saved.getId(),
                saved.getExpiresAt());
        Message presignedMessage = requestMessage;
        sendMessageToBoth(requesterId, receiverId, presignedMessage);

        return saved;
    }

    @Transactional
    public void acceptRequest(Long requestId, Long receiverId) {
        SelfieRequest request = selfieRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Selfie request not found"));

        if (!request.getReceiverId().equals(receiverId)) {
            throw new RuntimeException("Not authorized to accept");
        }

        if (!"PENDING".equals(request.getStatus())) {
            throw new RuntimeException("Request not pending");
        }

        LocalDateTime now = LocalDateTime.now();
        System.out.println("🕐 [SELFIE DEBUG] Accept received at: " + now);
        System.out.println("🕐 [SELFIE DEBUG] Request expires at: " + request.getExpiresAt());
        System.out.println("🕐 [SELFIE DEBUG] Time diff (seconds): " + java.time.Duration.between(now, request.getExpiresAt()).getSeconds());
        System.out.println("🕐 [SELFIE DEBUG] Server timezone: " + java.time.ZoneId.systemDefault());
        if (now.isAfter(request.getExpiresAt())) {
            System.out.println("❌ [SELFIE DEBUG] REJECTED: Request expired");
            request.setStatus("EXPIRED");
            selfieRequestRepository.save(request);
            notifyExpired(request);
            return;
        }
        System.out.println("✅ [SELFIE DEBUG] ACCEPTED: Sending START event");

        request.setStatus("ACCEPTED");
        request.setAcceptedAt(now);
        selfieRequestRepository.save(request);

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "START");
        payload.put("requestId", request.getId());
        payload.put("captureDelaySeconds", CAPTURE_DELAY_SECONDS);
        payload.put("deadlineMs", now.plusSeconds(CAPTURE_WINDOW_SECONDS)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());

        sendToUser(request.getRequesterId(), payload);
        sendToUser(request.getReceiverId(), payload);

        scheduleExpiry(request.getId(), now.plusSeconds(CAPTURE_WINDOW_SECONDS));
    }

    @Transactional
    public void cancelRequest(Long requestId, Long userId) {
        Optional<SelfieRequest> opt = selfieRequestRepository.findById(requestId);
        if (opt.isEmpty()) return;
        SelfieRequest request = opt.get();

        if (!request.getRequesterId().equals(userId) && !request.getReceiverId().equals(userId)) {
            return;
        }

        if ("COMPLETED".equals(request.getStatus()) || "EXPIRED".equals(request.getStatus())) {
            return;
        }

        request.setStatus("EXPIRED");
        selfieRequestRepository.save(request);
        cleanupResponses(requestId);
        notifyExpired(request);
    }

    @Transactional
    public void submitSelfie(Long requestId, Long userId, String objectKey, String contentHash) {
        SelfieRequest request = selfieRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Selfie request not found"));

        if (!"ACCEPTED".equals(request.getStatus())) {
            return;
        }

        LocalDateTime acceptedAt = request.getAcceptedAt();
        if (acceptedAt == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(acceptedAt.plusSeconds(CAPTURE_WINDOW_SECONDS))) {
            request.setStatus("EXPIRED");
            selfieRequestRepository.save(request);
            cleanupResponses(requestId);
            notifyExpired(request);
            return;
        }

        SelfieResponse response = selfieResponseRepository.findByRequestIdAndUserId(requestId, userId)
                .orElse(SelfieResponse.builder()
                        .requestId(requestId)
                        .userId(userId)
                        .build());

        response.setObjectKey(objectKey);
        response.setContentHash(contentHash);
        response.setCreatedAt(now);
        selfieResponseRepository.save(response);

        List<SelfieResponse> responses = selfieResponseRepository.findByRequestId(requestId);
        if (responses.size() < 2) {
            scheduleExpiry(requestId, acceptedAt.plusSeconds(CAPTURE_WINDOW_SECONDS));
            return;
        }

        Optional<SelfieResponse> requesterResp = responses.stream()
                .filter(r -> r.getUserId().equals(request.getRequesterId()))
                .findFirst();
        Optional<SelfieResponse> receiverResp = responses.stream()
                .filter(r -> r.getUserId().equals(request.getReceiverId()))
                .findFirst();

        if (requesterResp.isEmpty() || receiverResp.isEmpty()) {
            return;
        }

        if (requesterResp.get().getCreatedAt().isAfter(acceptedAt.plusSeconds(CAPTURE_WINDOW_SECONDS)) ||
                receiverResp.get().getCreatedAt().isAfter(acceptedAt.plusSeconds(CAPTURE_WINDOW_SECONDS))) {
            request.setStatus("EXPIRED");
            selfieRequestRepository.save(request);
            cleanupResponses(requestId);
            notifyExpired(request);
            return;
        }

        request.setStatus("COMPLETED");
        selfieRequestRepository.save(request);
        cancelExpiry(requestId);

        Message msgFromRequester = messageService.sendMessage(
                request.getRequesterId(),
                request.getReceiverId(),
                "[Selfie]",
                "IMAGE",
                requesterResp.get().getObjectKey(),
                null,
                requesterResp.get().getContentHash(),
                false);

        Message msgFromReceiver = messageService.sendMessage(
                request.getReceiverId(),
                request.getRequesterId(),
                "[Selfie]",
                "IMAGE",
                receiverResp.get().getObjectKey(),
                null,
                receiverResp.get().getContentHash(),
                false);

        sendMessageToBoth(request.getRequesterId(), request.getReceiverId(), msgFromRequester);
        sendMessageToBoth(request.getRequesterId(), request.getReceiverId(), msgFromReceiver);
    }

    private void sendToUser(Long userId, Map<String, Object> payload) {
        userRepository.findById(userId).ifPresent(user -> {
            if (user.getFirebaseUid() != null) {
                messagingTemplate.convertAndSendToUser(user.getFirebaseUid(), "/queue/selfie", payload);
            }
        });
    }

    private void sendMessageToBoth(Long userAId, Long userBId, Message message) {
        userRepository.findById(userAId).ifPresent(user -> {
            if (user.getFirebaseUid() != null) {
                messagingTemplate.convertAndSendToUser(user.getFirebaseUid(), "/queue/messages", message);
            }
        });
        userRepository.findById(userBId).ifPresent(user -> {
            if (user.getFirebaseUid() != null) {
                messagingTemplate.convertAndSendToUser(user.getFirebaseUid(), "/queue/messages", message);
            }
        });
    }

    private void scheduleExpiry(Long requestId, LocalDateTime deadline) {
        cancelExpiry(requestId);
        LocalDateTime now = LocalDateTime.now();
        long delayMs = Duration.between(now, deadline).toMillis();
        System.out.println("⏰ [SELFIE DEBUG] scheduleExpiry called for request " + requestId);
        System.out.println("⏰ [SELFIE DEBUG] Current time: " + now);
        System.out.println("⏰ [SELFIE DEBUG] Deadline: " + deadline);
        System.out.println("⏰ [SELFIE DEBUG] Delay (ms): " + delayMs);
        if (delayMs <= 0) {
            System.out.println("❌ [SELFIE DEBUG] Delay <= 0, expiring immediately!");
            expireRequest(requestId);
            return;
        }
        System.out.println("✅ [SELFIE DEBUG] Scheduling expiry in " + delayMs + "ms");
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            try {
                System.out.println("🔥 [SELFIE DEBUG] Scheduler firing for request " + requestId);
                expireRequest(requestId);
                System.out.println("🔥 [SELFIE DEBUG] expireRequest completed for request " + requestId);
            } catch (Exception e) {
                System.err.println("💣 [SELFIE DEBUG] Exception in scheduled expiry for request " + requestId);
                e.printStackTrace();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        expiryTasks.put(requestId, task);
    }

    private void cancelExpiry(Long requestId) {
        ScheduledFuture<?> task = expiryTasks.remove(requestId);
        if (task != null) {
            task.cancel(false);
        }
    }

    @Transactional
    protected void expireRequest(Long requestId) {
        System.out.println("💥 [SELFIE DEBUG] expireRequest called for request " + requestId);
        System.out.println("💥 [SELFIE DEBUG] Stack trace:");
        Thread.dumpStack();
        Optional<SelfieRequest> opt = selfieRequestRepository.findById(requestId);
        if (opt.isEmpty()) {
            System.out.println("❌ [SELFIE DEBUG] Request not found");
            return;
        }
        SelfieRequest request = opt.get();
        System.out.println("💥 [SELFIE DEBUG] Current status: " + request.getStatus());
        if ("COMPLETED".equals(request.getStatus()) || "EXPIRED".equals(request.getStatus())) {
            System.out.println("⚠️ [SELFIE DEBUG] Already " + request.getStatus() + ", skipping");
            return;
        }
        System.out.println("❌ [SELFIE DEBUG] Setting status to EXPIRED and notifying users");
        request.setStatus("EXPIRED");
        selfieRequestRepository.save(request);
        cleanupResponses(requestId);
        notifyExpired(request);
    }

    private void notifyExpired(SelfieRequest request) {
        System.out.println("📤 [SELFIE DEBUG] notifyExpired called for request " + request.getId());
        System.out.println("📤 [SELFIE DEBUG] Called from:");
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(6, stackTrace.length); i++) {
            System.out.println("    at " + stackTrace[i]);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "EXPIRED");
        payload.put("requestId", request.getId());
        sendToUser(request.getRequesterId(), payload);
        sendToUser(request.getReceiverId(), payload);
        System.out.println("✅ [SELFIE DEBUG] EXPIRED events sent to both users");
    }

    private void cleanupResponses(Long requestId) {
        List<SelfieResponse> responses = selfieResponseRepository.findByRequestId(requestId);
        for (SelfieResponse response : responses) {
            if (response.getContentHash() != null && mediaFileService.findByHash(response.getContentHash()).isPresent()) {
                continue;
            }
            fileStorageService.deleteObject(response.getObjectKey());
        }
        selfieResponseRepository.deleteByRequestId(requestId);
        cancelExpiry(requestId);
    }
}
