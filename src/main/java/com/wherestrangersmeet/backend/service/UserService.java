package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.model.UserPhoto;
import com.wherestrangersmeet.backend.repository.UserPhotoRepository;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserPhotoRepository userPhotoRepository;
    private final FileStorageService fileStorageService;

    public Optional<User> getUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid);
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User createUserIfNew(String firebaseUid, String email, String name, String avatarUrl) {
        // First check by Firebase UID
        Optional<User> existing = userRepository.findByFirebaseUid(firebaseUid);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isBanned()) {
                throw new RuntimeException("Account deactivated");
            }
            System.out.println("User already exists with UID: " + firebaseUid);
            return user;
        }

        // Check by email to link accounts
        Optional<User> existingByEmail = userRepository.findByEmail(email);
        if (existingByEmail.isPresent()) {
            System.out.println("User exists with email, linking Firebase UID: " + firebaseUid);
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
            System.out.println("Creating new user: " + email);
            return userRepository.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race condition: another thread just created this user
            // Retry the lookup
            System.out.println("Duplicate key detected, retrying lookup for: " + firebaseUid);

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
    public UserPhoto addUserPhoto(Long userId, String fileKey) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String publicUrl = fileStorageService.getPublicUrl(fileKey);

        UserPhoto photo = new UserPhoto();
        photo.setUser(user);
        photo.setUrl(publicUrl);

        // Set as avatar if none exists
        if (user.getAvatarUrl() == null) {
            user.setAvatarUrl(publicUrl);
            userRepository.save(user); // Save user to update avatar
        }

        return userPhotoRepository.save(photo);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setBanned(true);
        userRepository.save(user); // Soft delete via ban
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
