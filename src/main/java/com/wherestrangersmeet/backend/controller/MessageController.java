package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.MessageService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);
    private final MessageService messageService;
    private final UserService userService;
    private final com.wherestrangersmeet.backend.service.UserCache userCache;
    private final com.wherestrangersmeet.backend.service.FileStorageService fileStorageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.wherestrangersmeet.backend.service.MediaFileService mediaFileService;

    // Send a message via HTTP
    @PostMapping
    public ResponseEntity<Message> sendMessage(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, Object> payload) {

        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User sender = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Long receiverId = ((Number) payload.get("receiverId")).longValue();

        if (sender.getId().equals(receiverId)) {
            return ResponseEntity.badRequest().build();
        }

        String text = (String) payload.get("text");
        String messageType = (String) payload.getOrDefault("messageType", "TEXT");
        String attachmentUrl = (String) payload.get("attachmentUrl");
        String attachmentHash = (String) payload.get("attachmentHash");

        Object replyToIdObj = payload.get("replyToId");
        Long replyToId = replyToIdObj != null ? ((Number) replyToIdObj).longValue() : null;

        Message message = messageService.sendMessage(sender.getId(), receiverId, text, messageType, attachmentUrl,
                replyToId, attachmentHash);
        return ResponseEntity.ok(message);
    }

    // Send a message via WebSocket
    @MessageMapping("/chat.sendMessage")
    public void handleWebSocketMessage(Map<String, Object> payload, Principal principal) {
        if (principal == null) {
            log.warn("❌ WebSocket message received without authentication");
            return;
        }

        try {
            // Get sender ID from cache (0ms instead of 10-30ms DB query)
            Long senderId;
            try {
                senderId = userCache.getUserId(principal.getName());
            } catch (RuntimeException e) {
                // SELF-HEALING: User not found in cache/DB - JIT sync from Firebase
                log.warn("⚠️ User not found in DB, attempting JIT sync from Firebase: {}", principal.getName());

                try {
                    // Fetch user details from Firebase Admin SDK
                    com.google.firebase.auth.UserRecord firebaseUser =
                        com.google.firebase.auth.FirebaseAuth.getInstance().getUser(principal.getName());

                    String email = firebaseUser.getEmail();
                    String name = firebaseUser.getDisplayName();
                    String avatarUrl = firebaseUser.getPhotoUrl();

                    // Create user in DB
                    User newUser = userService.createUserIfNew(
                        principal.getName(),
                        email != null ? email : "unknown@unknown.com",
                        name != null ? name : "User",
                        avatarUrl
                    );

                    log.info("✅ JIT sync successful for user: {}", newUser.getEmail());
                    senderId = newUser.getId();

                } catch (com.google.firebase.auth.FirebaseAuthException fae) {
                    log.error("❌ Failed to fetch user from Firebase: {}", fae.getMessage());
                    throw new RuntimeException("User not found and Firebase sync failed", fae);
                }
            }

            Long receiverId = ((Number) payload.get("receiverId")).longValue();

            if (senderId.equals(receiverId)) {
                log.warn("❌ Cannot send message to yourself");
                return;
            }

            String text = (String) payload.get("text");
            String messageType = (String) payload.getOrDefault("messageType", "TEXT");
            String attachmentUrl = (String) payload.get("attachmentUrl");
            String attachmentHash = (String) payload.get("attachmentHash");

            Object replyToIdObj = payload.get("replyToId");
            Long replyToId = replyToIdObj != null ? ((Number) replyToIdObj).longValue() : null;

            // Save message (now much faster with cached user lookups)
            Message savedMessage = messageService.sendMessage(senderId, receiverId, text, messageType,
                    attachmentUrl, replyToId, attachmentHash, false);

            // Get receiver Firebase UID from cache (0ms instead of 10-30ms)
            String receiverFirebaseUid = userCache.getFirebaseUid(receiverId);

            // Send to RECEIVER
            messagingTemplate.convertAndSendToUser(
                    receiverFirebaseUid,
                    "/queue/messages",
                    savedMessage);

            // Send to SENDER (confirmation)
            messagingTemplate.convertAndSendToUser(
                    principal.getName(),
                    "/queue/messages",
                    savedMessage);

            log.info("✅ WebSocket message sent successfully: {}", savedMessage.getId());

        } catch (Exception e) {
            log.error("❌ Error handling WebSocket message", e);
        }
    }

    // Handle typing indicator via WebSocket
    @MessageMapping("/chat.typing")
    public void handleTypingStatus(Map<String, Object> payload, Principal principal) {
        if (principal == null) {
            return;
        }

        try {
            Long receiverId = ((Number) payload.get("receiverId")).longValue();
            Boolean isTyping = (Boolean) payload.get("isTyping");

            // Get receiver's Firebase UID
            userService.getUserById(receiverId).ifPresent(receiver -> {
                if (receiver.getFirebaseUid() != null) {
                    // Send typing status to receiver
                    Map<String, Object> typingUpdate = new java.util.HashMap<>();
                    typingUpdate.put("isTyping", isTyping);
                    typingUpdate.put("timestamp", System.currentTimeMillis());

                    messagingTemplate.convertAndSendToUser(
                            receiver.getFirebaseUid(),
                            "/queue/typing",
                            typingUpdate);
                }
            });

        } catch (Exception e) {
            log.error("❌ Error handling typing status", e);
        }
    }

    // Generate Presigned Upload URL for Message Media
    @PostMapping("/upload-url")
    public ResponseEntity<Map<String, String>> getUploadUrl(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, String> payload) {

        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String filename = payload.get("filename");
        String contentHash = payload.get("hash");

        if (contentHash != null && !contentHash.isBlank()) {
            java.util.Optional<com.wherestrangersmeet.backend.model.MediaFile> existing = mediaFileService
                    .findByHash(contentHash);
            if (existing.isPresent()) {
                java.util.Map<String, String> result = new java.util.HashMap<>();
                result.put("key", existing.get().getObjectKey());
                result.put("exists", "true");
                return ResponseEntity.ok(result);
            }
        }

        // Enforce message-media directory
        Map<String, String> result = fileStorageService
                .generatePresignedUploadUrl("message-media", filename);
        result.put("exists", "false");
        return ResponseEntity.ok(result);
    }

    // Get conversation with a specific user
    @GetMapping("/{otherUserId}")
    public ResponseEntity<List<Message>> getConversation(
            @AuthenticationPrincipal FirebaseToken principal,
            @PathVariable Long otherUserId,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String beforeCreatedAt,
            @RequestParam(required = false) Long beforeId) {

        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        java.time.LocalDateTime beforeCreated = null;
        if (beforeCreatedAt != null && beforeId != null) {
            try {
                beforeCreated = java.time.OffsetDateTime.parse(beforeCreatedAt).toLocalDateTime();
            } catch (java.time.format.DateTimeParseException e) {
                beforeCreated = java.time.LocalDateTime.parse(beforeCreatedAt);
            }
        }

        List<Message> messages = messageService.getConversation(currentUser.getId(), otherUserId, size, beforeCreated,
                beforeId);
        return ResponseEntity.ok(messages);
    }

    // Get list of conversations (latest message per user)
    @GetMapping("/conversations")
    public ResponseEntity<List<Map<String, Object>>> getConversations(
            @AuthenticationPrincipal FirebaseToken principal) {

        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<Map<String, Object>> conversations = messageService.getConversations(currentUser.getId());
        return ResponseEntity.ok(conversations);
    }

    @PutMapping("/mark-read/{senderId}")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal FirebaseToken principal,
            @PathVariable Long senderId) {

        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        messageService.markAsRead(currentUser.getId(), senderId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMessage(
            @AuthenticationPrincipal FirebaseToken principal,
            @PathVariable Long id) {

        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        try {
            messageService.deleteMessage(id, currentUser.getId());
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }
}
