package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.MessageService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final UserService userService;
    private final com.wherestrangersmeet.backend.service.FileStorageService fileStorageService;

    // Send a message
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
            @PathVariable Long otherUserId) {

        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<Message> messages = messageService.getConversation(currentUser.getId(), otherUserId);
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
}
