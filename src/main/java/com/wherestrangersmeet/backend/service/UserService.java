package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.model.UserPhoto;
import com.wherestrangersmeet.backend.repository.UserPhotoRepository;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

import org.springframework.messaging.simp.SimpMessagingTemplate;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository userRepository;
    private final UserPhotoRepository userPhotoRepository;
    private final FileStorageService fileStorageService;
    private final OpenAIService openAIService;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserCache userCache;

    public Optional<User> getUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid);
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public org.springframework.data.domain.Page<User> getFeedUsers(String currentUid, int page, int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return userRepository.findByFirebaseUidNotAndPhotosIsNotEmpty(currentUid, pageable);
    }

    @Transactional
    public User createUserIfNew(String firebaseUid, String email, String name, String avatarUrl) {
        // First check by Firebase UID
        Optional<User> existing = userRepository.findByFirebaseUid(firebaseUid);
        if (existing.isPresent()) {
            log.info("User already exists with UID: {}", firebaseUid);
            return existing.get();
        }

        // Check by email to link accounts
        Optional<User> existingByEmail = userRepository.findByEmail(email);
        if (existingByEmail.isPresent()) {
            log.info("User exists with email, linking Firebase UID: {}", firebaseUid);
            User user = existingByEmail.get();
            user.setFirebaseUid(firebaseUid);
            if (avatarUrl != null)
                user.setAvatarUrl(avatarUrl);
            return userRepository.save(user);
        }

        // Try to create new user
        try {
            User user = new User();
            user.setFirebaseUid(firebaseUid);
            user.setEmail(email);
            user.setName(name != null ? name : email.split("@")[0]);
            user.setAvatarUrl(avatarUrl);
            log.info("Creating new user: {}", email);
            return userRepository.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race condition: another thread just created this user
            // Retry the lookup
            log.warn("Duplicate key detected, retrying lookup for: {}", firebaseUid);

            existing = userRepository.findByFirebaseUid(firebaseUid);
            if (existing.isPresent()) {
                return existing.get();
            }

            existingByEmail = userRepository.findByEmail(email);
            if (existingByEmail.isPresent()) {
                return existingByEmail.get();
            }

            // If still not found, rethrow the exception
            throw e;
        }
    }

    @Transactional
    public User updateOnboardingDetails(Long id, User.Gender gender, String futureGoals,
            User.OccupationStatus occupationStatus, String occupationTitle, String name,
            String institution, String occupationYear, String occupationDescription, List<String> interestTags) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (name != null && !name.trim().isEmpty()) {
            user.setName(name);
        }
        if (gender != null)
            user.setGender(gender);
        if (futureGoals != null)
            user.setFutureGoals(futureGoals);
        if (occupationStatus != null)
            user.setOccupationStatus(occupationStatus);
        if (occupationTitle != null)
            user.setOccupationTitle(occupationTitle);
        if (institution != null)
            user.setInstitution(institution);
        if (occupationYear != null)
            user.setOccupationYear(occupationYear);
        if (occupationDescription != null)
            user.setOccupationDescription(occupationDescription);
        if (interestTags != null) {
            user.getInterestTags().clear(); // Clear existing
            user.getInterestTags().addAll(interestTags);
        }

        return userRepository.save(user);
    }

    @Transactional
    public void updateVoiceIntro(Long userId, String voiceIntroUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setVoiceIntroUrl(voiceIntroUrl);
        userRepository.save(user);
    }

    @Transactional
    public User updatePhoneNumber(Long id, String phoneNumber) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
            user.setPhoneNumber(phoneNumber);
            return userRepository.save(user);
        }
        return user;
    }

    @Transactional
    public UserPhoto addUserPhoto(Long userId, String fileKey, boolean skipVerification) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String publicUrl = fileStorageService.getPublicUrl(fileKey);

        // --- VERIFICATION START ---
        if (!skipVerification) {
            // 1. Gather reference URLs (existing photos) - Convert keys to Presigned URLs
            List<String> referenceUrls = new ArrayList<>();
            for (UserPhoto existing : user.getPhotos()) {
                String refKey = existing.getUrl();
                // If the stored URL is just a key, generate a presigned URL
                String presignedRef = fileStorageService.generatePresignedUrl(refKey);
                if (presignedRef != null) {
                    referenceUrls.add(presignedRef);
                }
            }

            // 2. Call OpenAI Service
            try {
                // Generate presigned URL for the NEW photo specifically for verification
                String verificationUrl = fileStorageService.generatePresignedUrl(fileKey);
                Map<String, Object> verificationResult;

                // Check if verificationUrl is valid (starts with http)
                if (verificationUrl != null && verificationUrl.startsWith("http")) {
                    log.info("Verifying with URL: {}", verificationUrl);
                    verificationResult = openAIService.verifyPhotoUrl(verificationUrl, referenceUrls);
                } else {
                    // Fallback: URL generation failed, use Base64
                    log.warn("URL generation failed (returned key), falling back to Base64 verification for key: {}",
                            fileKey);
                    try (java.io.InputStream is = fileStorageService.downloadFile(fileKey)) {
                        byte[] imageBytes = is.readAllBytes();
                        String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
                        // Guess mime type from extension
                        String mimeType = "image/jpeg";
                        if (fileKey.toLowerCase().endsWith(".png"))
                            mimeType = "image/png";

                        verificationResult = openAIService.verifyPhotoBase64(base64Image, mimeType, referenceUrls);
                    }
                }

                log.info("Verifying new profile photo for user {} against {} references", userId, referenceUrls.size());

                // 3. Check result
                boolean isValid = (boolean) verificationResult.getOrDefault("valid", false);
                if (!isValid) {
                    String errorMsg = (String) verificationResult.getOrDefault("message", "Photo verification failed");
                    log.warn("Photo verification failed for user {}: {}", userId, errorMsg);
                    throw new RuntimeException("Verification failed: " + errorMsg);
                }

                log.info("Photo verification successful for user {}", userId);

            } catch (RuntimeException e) {
                throw e; // Re-throw our validation errors
            } catch (Exception e) {
                log.error("Error during profile photo verification: {}", e.getMessage());
                // Fail safe: If AI service is down, blocking for safety as requested:
                throw new RuntimeException("Validation service unavailable. Please try again later.");
            }
        } // End of if (!skipVerification)
          // --- VERIFICATION END ---

        UserPhoto photo = new UserPhoto();
        photo.setUser(user);
        photo.setUrl(publicUrl);

        // Always update avatar URL to the new photo
        user.setAvatarUrl(publicUrl);
        user.setAvatarCropX(null);
        user.setAvatarCropY(null);
        user.setAvatarCropScale(null);
        userRepository.save(user);

        return userPhotoRepository.save(photo);
    }

    @Transactional
    public void deleteUserPhoto(Long userId, Long photoId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getPhotos().size() <= 2) {
            throw new IllegalStateException("Minimum 2 photos required");
        }

        UserPhoto photo = userPhotoRepository.findById(photoId)
                .orElseThrow(() -> new RuntimeException("Photo not found"));

        if (!photo.getUser().getId().equals(userId)) {
            throw new RuntimeException("Photo does not belong to user");
        }

        boolean wasAvatar = user.getAvatarUrl() != null && user.getAvatarUrl().equals(photo.getUrl());

        user.getPhotos().remove(photo);
        userPhotoRepository.delete(photo);

        if (wasAvatar) {
            String newAvatar = user.getPhotos().isEmpty() ? null : user.getPhotos().get(0).getUrl();
            user.setAvatarUrl(newAvatar);
        }

        userRepository.save(user);
    }

    @Transactional
    public void setAvatarFromPhoto(Long userId, Long photoId) {
        setAvatarFromPhoto(userId, photoId, null, null, null);
    }

    @Transactional
    public void setAvatarFromPhoto(Long userId, Long photoId, Double cropX, Double cropY, Double cropScale) {
        UserPhoto photo = userPhotoRepository.findById(photoId)
                .orElseThrow(() -> new RuntimeException("Photo not found"));

        if (!photo.getUser().getId().equals(userId)) {
            throw new RuntimeException("Photo does not belong to user");
        }

        User user = photo.getUser();
        user.setAvatarUrl(photo.getUrl());
        user.setAvatarCropX(cropX);
        user.setAvatarCropY(cropY);
        user.setAvatarCropScale(cropScale);
        userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        log.info("🗑️ Soft deleting user: {} ({})", user.getId(), user.getEmail());

        // Delete from Firebase Auth first
        if (user.getFirebaseUid() != null) {
            try {
                com.google.firebase.auth.FirebaseAuth.getInstance().deleteUser(user.getFirebaseUid());
                log.info("✅ Deleted user from Firebase: {}", user.getFirebaseUid());
            } catch (com.google.firebase.auth.FirebaseAuthException e) {
                log.warn("⚠️ Failed to delete user from Firebase: {}", e.getMessage());
                // Continue with soft delete even if Firebase fails
            }
        }

        // Soft delete: Mark as deleted and anonymize personal data
        user.setDeletedAt(LocalDateTime.now());

        // Anonymize user data (preserve ID for foreign key relationships)
        user.setName("Deleted User");
        user.setEmail("deleted_" + user.getId() + "@deleted.com");
        user.setFirebaseUid("deleted_" + user.getId()); // Can't be null due to NOT NULL constraint
        user.setPhoneNumber(null);
        user.setAvatarUrl(null);
        user.setBio(null);
        user.setFcmToken(null);
        user.setIsOnline(false);

        // Clear onboarding data
        user.setFutureGoals(null);
        user.setOccupationTitle(null);
        user.setInstitution(null);
        user.setOccupationYear(null);
        user.setOccupationDescription(null);
        user.setInterestTags(new ArrayList<>());

        // Delete user photos
        user.getPhotos().clear();

        // Invalidate cache before saving to prevent stale data
        userCache.invalidateByFirebaseUid(user.getFirebaseUid());
        userCache.invalidateByUserId(user.getId());
        log.info("🗑️ Cache invalidated for deleted user");

        userRepository.save(user);
        log.info("✅ User soft deleted and anonymized successfully");
    }

    @Transactional
    public void updateUserStatus(Long userId, boolean isOnline, String source) {
        userRepository.findById(userId).ifPresent(user -> {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            String timestamp = LocalDateTime.now().format(formatter);

            // Log BEFORE update to see previous state
            log.info("┌─────────────────────────────────────────────────────");
            log.info("│ 📊 PRESENCE UPDATE REQUEST");
            log.info("│ Time: {}", timestamp);
            log.info("│ User ID: {}", userId);
            log.info("│ Name: {}", user.getName());
            log.info("│ Current State: {}", user.getIsOnline() ? "ONLINE" : "OFFLINE");
            log.info("│ Current lastActive: {}", user.getLastActive());
            log.info("│ New State: {}", isOnline ? "ONLINE" : "OFFLINE");
            log.info("│ Source: {}", source);

            user.setIsOnline(isOnline);

            // Only update lastActive when marking ONLINE (preserves actual last activity
            // time)
            if (isOnline) {
                user.setLastActive(LocalDateTime.now()); // Uses system default timezone (UTC)
                log.info("│ Updated lastActive: {}", user.getLastActive());
            } else {
                log.info("│ Preserved lastActive: {}", user.getLastActive());
            }

            userRepository.save(user);

            // Broadcast presence update to ALL users via WebSocket
            Map<String, Object> presenceUpdate = new HashMap<>();
            presenceUpdate.put("userId", userId);
            presenceUpdate.put("isOnline", isOnline);
            presenceUpdate.put("timestamp", System.currentTimeMillis());

            messagingTemplate.convertAndSend("/topic/presence", presenceUpdate);

            log.info("│ Broadcast: ✅ Sent to /topic/presence");
            log.info("└─────────────────────────────────────────────────────");
        });
    }

    // Backward compatibility - calls new method with "Unknown-Source"
    @Transactional
    public void updateUserStatus(Long userId, boolean isOnline) {
        updateUserStatus(userId, isOnline, "Unknown-Source");
    }

    @Transactional
    public void updateFcmToken(Long userId, String token) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setFcmToken(token);
            userRepository.save(user);
        });
    }

    @Transactional
    public User updateInterestTags(Long id, List<String> tags) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.getInterestTags().clear();
        if (tags != null) {
            user.getInterestTags().addAll(tags);
        }
        return userRepository.save(user);
    }
}
