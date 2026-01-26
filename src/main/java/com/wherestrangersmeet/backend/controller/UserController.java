package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.FileStorageService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final FileStorageService fileStorageService;

    /**
     * GET /api/users/me
     * Get current user profile
     */
    @GetMapping("/me")
    public ResponseEntity<?> getUserProfile(@AuthenticationPrincipal FirebaseToken principal) {
        // Automatically create user if they don't exist (Sync from Firebase)
        User user = userService.createUserIfNew(
                principal.getUid(),
                principal.getEmail(),
                principal.getName(),
                principal.getPicture());
        return ResponseEntity.ok(user);
    }

    /**
     * POST /api/users/onboarding
     * Update user details during onboarding (Basic Info & Occupation)
     */
    @PostMapping("/onboarding")
    public ResponseEntity<?> updateOnboarding(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, Object> request) {

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String genderStr = (String) request.get("gender");
        String futureGoals = (String) request.get("futureGoals");
        String occupationStatusStr = (String) request.get("occupationStatus");
        String occupationTitle = (String) request.get("occupationTitle");

        User.Gender gender = null;
        if (genderStr != null) {
            try {
                gender = User.Gender.valueOf(genderStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Ignore invalid
            }
        }

        User.OccupationStatus occupationStatus = null;
        if (occupationStatusStr != null) {
            try {
                occupationStatus = User.OccupationStatus.valueOf(occupationStatusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Ignore invalid
            }
        }

        User updatedUser = userService.updateOnboardingDetails(user.getId(), gender, futureGoals, occupationStatus,
                occupationTitle);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * POST /api/users/phone-number
     * Update user phone number
     */
    @PostMapping("/phone-number")
    public ResponseEntity<?> updatePhoneNumber(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, String> request) {

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String phoneNumber = request.get("phoneNumber");
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Phone number is required"));
        }

        User updatedUser = userService.updatePhoneNumber(user.getId(), phoneNumber);
        return ResponseEntity.ok(updatedUser);
    }

    /**
     * POST /api/users/photos/upload-url
     * Generate presigned URL for user profile photo upload
     */
    @PostMapping("/photos/upload-url")
    public ResponseEntity<?> getPhotoUploadUrl(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, String> request) {

        // Verify user exists
        // Verify user exists or create
        userService.createUserIfNew(
                principal.getUid(),
                principal.getEmail(),
                principal.getName(),
                principal.getPicture());

        try {
            String filename = request.get("filename");
            // Generate presigned upload URL for "user-photos" folder
            Map<String, String> uploadData = fileStorageService.generatePresignedUploadUrl("user-photos", filename);
            return ResponseEntity.ok(uploadData);

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/users/photos/confirm
     * Confirm photo upload and save to user_photos
     */
    @PostMapping("/photos/confirm")
    public ResponseEntity<?> confirmPhotoUpload(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, String> request) {

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String key = request.get("key");
        if (key == null || key.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Key is required"));
        }

        try {
            var photo = userService.addUserPhoto(user.getId(), key);
            return ResponseEntity.ok(photo);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
