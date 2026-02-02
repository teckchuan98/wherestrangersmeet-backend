package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.SelfieService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class SelfieController {

    private final UserService userService;
    private final SelfieService selfieService;

    @MessageMapping("/selfie.request")
    public void requestSelfie(Map<String, Object> payload, Principal principal) {
        System.out.println("📸 [SELFIE DEBUG] Received selfie.request");
        if (principal == null) {
            System.out.println("❌ [SELFIE DEBUG] Principal is null");
            return;
        }
        User sender = userService.getUserByFirebaseUid(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found: " + principal.getName()));

        Long receiverId = ((Number) payload.get("receiverId")).longValue();
        System.out.println("📸 [SELFIE DEBUG] Sender: " + sender.getId() + ", Receiver: " + receiverId);
        if (sender.getId().equals(receiverId)) {
            System.out.println("❌ [SELFIE DEBUG] Cannot send selfie request to self");
            return;
        }
        System.out.println("✅ [SELFIE DEBUG] Creating selfie request");
        selfieService.createRequest(sender.getId(), receiverId);
    }

    @MessageMapping("/selfie.accept")
    public void acceptSelfie(Map<String, Object> payload, Principal principal) {
        if (principal == null) return;
        User receiver = userService.getUserByFirebaseUid(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found: " + principal.getName()));
        Long requestId = ((Number) payload.get("requestId")).longValue();
        selfieService.acceptRequest(requestId, receiver.getId());
    }

    @MessageMapping("/selfie.cancel")
    public void cancelSelfie(Map<String, Object> payload, Principal principal) {
        System.out.println("🚫 [SELFIE DEBUG] Received selfie.cancel");
        if (principal == null) {
            System.out.println("❌ [SELFIE DEBUG] Principal is null");
            return;
        }
        User user = userService.getUserByFirebaseUid(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found: " + principal.getName()));
        Long requestId = ((Number) payload.get("requestId")).longValue();
        System.out.println("🚫 [SELFIE DEBUG] User " + user.getId() + " cancelling request " + requestId);
        selfieService.cancelRequest(requestId, user.getId());
        System.out.println("✅ [SELFIE DEBUG] Cancel completed");
    }

    @MessageMapping("/selfie.image")
    public void submitSelfie(Map<String, Object> payload, Principal principal) {
        if (principal == null) return;
        User user = userService.getUserByFirebaseUid(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found: " + principal.getName()));
        Long requestId = ((Number) payload.get("requestId")).longValue();
        String attachmentUrl = (String) payload.get("attachmentUrl");
        String attachmentHash = (String) payload.get("attachmentHash");
        selfieService.submitSelfie(requestId, user.getId(), attachmentUrl, attachmentHash);
    }
}
