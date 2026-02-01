package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.MessageService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
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

    private final MessageService messageService;
    private final UserService userService;
    private final com.wherestrangersmeet.backend.service.FileStorageService fileStorageService;
    private final SimpMessagingTemplate messagingTemplate;

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

        Object replyToIdObj = payload.get("replyToId");
        Long replyToId = replyToIdObj != null ? ((Number) replyToIdObj).longValue() : null;

        Message message = messageService.sendMessage(sender.getId(), receiverId, text, messageType, attachmentUrl,
                replyToId);
        return ResponseEntity.ok(message);
    }

    // Send a message via WebSocket
    @MessageMapping("/chat.sendMessage")
    public void handleWebSocketMessage(Map<String, Object> payload, Principal principal) {
        if (principal == null) {
            System.err.println("❌ WebSocket message received without authentication");
            return;
        }

        try {
            // Get sender from Firebase UID in principal
            User sender = userService.getUserByFirebaseUid(principal.getName())
                    .orElseThrow(() -> new RuntimeException("User not found: " + principal.getName()));

            Long receiverId = ((Number) payload.get("receiverId")).longValue();

            if (sender.getId().equals(receiverId)) {
                System.err.println("❌ Cannot send message to yourself");
                return;
            }

            String text = (String) payload.get("text");
            String messageType = (String) payload.getOrDefault("messageType", "TEXT");
            String attachmentUrl = (String) payload.get("attachmentUrl");

            Object replyToIdObj = payload.get("replyToId");
            Long replyToId = replyToIdObj != null ? ((Number) replyToIdObj).longValue() : null;

            // Save message
            Message savedMessage = messageService.sendMessage(sender.getId(), receiverId, text, messageType,
                    attachmentUrl, replyToId, false);

            // Send to RECEIVER (ensure WS payload includes presigned URL)
            userService.getUserById(receiverId).ifPresent(receiver -> {
                if (receiver.getFirebaseUid() != null) {
                    messagingTemplate.convertAndSendToUser(
                            receiver.getFirebaseUid(),
                            "/queue/messages",
                            savedMessage);
                }
            });

            messagingTemplate.convertAndSendToUser(
                    principal.getName(),
                    "/queue/messages",
                    savedMessage);

            System.out.println("✅ WebSocket message sent successfully: " + savedMessage.getId());

        } catch (Exception e) {
            System.err.println("❌ Error handling WebSocket message: " + e.getMessage());
            e.printStackTrace();
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
            System.err.println("❌ Error handling typing status: " + e.getMessage());
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
        // Enforce message-media directory
        Map<String, String> result = fileStorageService
                .generatePresignedUploadUrl("message-media", filename);
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
