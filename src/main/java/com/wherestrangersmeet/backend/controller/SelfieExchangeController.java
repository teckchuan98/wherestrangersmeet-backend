package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.SelfieExchangeService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/selfie-exchanges")
@RequiredArgsConstructor
public class SelfieExchangeController {

    private final SelfieExchangeService selfieExchangeService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<?> createRequest(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, Object> payload) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        try {
            Long receiverId = ((Number) payload.get("receiverId")).longValue();
            return ResponseEntity.ok(selfieExchangeService.createRequest(currentUser.getId(), receiverId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> acceptRequest(
            @AuthenticationPrincipal FirebaseToken principal,
            @PathVariable Long id) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        try {
            return ResponseEntity.ok(selfieExchangeService.acceptRequest(id, currentUser.getId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/upload")
    public ResponseEntity<?> uploadSelfie(
            @AuthenticationPrincipal FirebaseToken principal,
            @PathVariable Long id,
            @RequestBody Map<String, String> payload) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String attachmentUrl = payload.get("attachmentUrl");
        String attachmentHash = payload.get("attachmentHash");
        if (attachmentUrl == null || attachmentUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attachmentUrl is required");
        }

        try {
            return ResponseEntity.ok(selfieExchangeService.uploadSelfie(id, currentUser.getId(), attachmentUrl, attachmentHash));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/active/{otherUserId}")
    public ResponseEntity<?> getActiveExchange(
            @AuthenticationPrincipal FirebaseToken principal,
            @PathVariable Long otherUserId) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        User currentUser = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Object response = selfieExchangeService.getActiveExchange(currentUser.getId(), otherUserId);
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(response);
    }
}
