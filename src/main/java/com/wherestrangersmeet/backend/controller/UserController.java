package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.FileStorageService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.List;

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
        System.out.println("========== GET /api/users/me ==========");
        System.out.println("Principal: " + (principal != null ? principal.getUid() : "NULL"));

        if (principal == null) {
            System.err.println("ERROR: FirebaseToken principal is NULL - authentication failed!");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Authentication failed - no valid Firebase token"));
        }

        System.out.println("Email: " + principal.getEmail());
        System.out.println("Name: " + principal.getName());

        // Automatically create user if they don't exist (Sync from Firebase)
        User user = userService.createUserIfNew(
                principal.getUid(),
                principal.getEmail(),
                principal.getName(),
                principal.getPicture());

        System.out.println("User retrieved/created: ID=" + user.getId() + ", UID=" + user.getFirebaseUid());
        System.out.println("Photos found: " + (user.getPhotos() != null ? user.getPhotos().size() : "NULL"));

        // Convert R2 keys to Presigned URLs
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().startsWith("http")) {
            String presigned = fileStorageService.generatePresignedUrl(user.getAvatarUrl());
            user.setAvatarUrl(presigned);
        }

        if (user.getPhotos() != null) {
            user.getPhotos().forEach(photo -> {
                if (photo.getUrl() != null && !photo.getUrl().startsWith("http")) {
                    String presigned = fileStorageService.generatePresignedUrl(photo.getUrl());
                    photo.setUrl(presigned);
                }
            });
        }

        System.out.println("========================================");

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

        System.out.println("========== ONBOARDING REQUEST ==========");
        System.out.println("Principal: " + (principal != null ? principal.getUid() : "NULL"));
        System.out.println("Request body: " + request);

        if (principal == null) {
            System.err.println("ERROR: FirebaseToken principal is NULL - authentication failed!");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Authentication failed - no valid Firebase token"));
        }

        System.out.println("Looking up user by Firebase UID: " + principal.getUid());

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> {
                    System.err.println("ERROR: User not found in database for UID: " + principal.getUid());
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
                });

        System.out.println("User found: ID=" + user.getId() + ", Name=" + user.getName());

        String genderStr = (String) request.get("gender");
        String futureGoals = (String) request.get("futureGoals");
        String occupationStatusStr = (String) request.get("occupationStatus");
        String occupationTitle = (String) request.get("occupationTitle");
        String name = (String) request.get("name");
        String institution = (String) request.get("institution");
        String occupationYear = (String) request.get("occupationYear");
        String occupationDescription = (String) request.get("occupationDescription");
        List<String> interestTags = (List<String>) request.get("interestTags");

        User.Gender gender = null;
        if (genderStr != null) {
            try {
                gender = User.Gender.valueOf(genderStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid gender value: " + genderStr);
            }
        }

        User.OccupationStatus occupationStatus = null;
        if (occupationStatusStr != null) {
            try {
                occupationStatus = User.OccupationStatus.valueOf(occupationStatusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid occupation status value: " + occupationStatusStr);
            }
        }

        User updatedUser = userService.updateOnboardingDetails(user.getId(), gender, futureGoals, occupationStatus,
                occupationTitle, name, institution, occupationYear, occupationDescription, interestTags);

        System.out.println("Onboarding updated successfully for user: " + updatedUser.getId());
        System.out.println("========================================");

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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String key = request.get("key");
        if (key == null || key.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Key is required"));
        }

        try {
            var photo = userService.addUserPhoto(user.getId(), key);

            // Convert to Presigned URL for immediate display
            if (photo.getUrl() != null && !photo.getUrl().startsWith("http")) {
                String presigned = fileStorageService.generatePresignedUrl(photo.getUrl());
                photo.setUrl(presigned);
            }

            return ResponseEntity.ok(photo);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/users/interests
     * Update user interest tags
     */
    @PutMapping("/interests")
    public ResponseEntity<?> updateInterestTags(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, List<String>> request) {

        System.out.println("========== UPDATE INTERESTS ==========");
        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<String> tags = request.get("tags");
        User updatedUser = userService.updateInterestTags(user.getId(), tags);

        return ResponseEntity.ok(updatedUser);
    }

    /**
     * DELETE /api/users/me
     * Delete user account
     */
    @DeleteMapping("/me")
    public ResponseEntity<?> deleteAccount(@AuthenticationPrincipal FirebaseToken principal) {
        System.out.println("========== DELETE ACCOUNT ==========");
        if (principal == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        userService.deleteUser(user.getId());
        // Note: Client should also sign out from Firebase

        return ResponseEntity.ok(Map.of("message", "Account deleted successfully"));
    }
}
