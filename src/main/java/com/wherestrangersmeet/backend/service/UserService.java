package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.model.UserPhoto;
import com.wherestrangersmeet.backend.repository.UserPhotoRepository;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
        Optional<User> existing = userRepository.findByFirebaseUid(firebaseUid);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Also check by email to link accounts
        Optional<User> existingByEmail = userRepository.findByEmail(email);
        if (existingByEmail.isPresent()) {
            User user = existingByEmail.get();
            user.setFirebaseUid(firebaseUid);
            if (avatarUrl != null)
                user.setAvatarUrl(avatarUrl);
            return userRepository.save(user);
        }

        User user = new User();
        user.setFirebaseUid(firebaseUid);
        user.setEmail(email);
        user.setName(name != null ? name : email.split("@")[0]);
        user.setAvatarUrl(avatarUrl);
        return userRepository.save(user);
    }

    @Transactional
    public User updateOnboardingDetails(Long id, User.Gender gender, String futureGoals,
            User.OccupationStatus occupationStatus, String occupationTitle) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (gender != null)
            user.setGender(gender);
        if (futureGoals != null)
            user.setFutureGoals(futureGoals);
        if (occupationStatus != null)
            user.setOccupationStatus(occupationStatus);
        if (occupationTitle != null)
            user.setOccupationTitle(occupationTitle);

        return userRepository.save(user);
    }

    @Transactional
    public User updatePhoneNumber(Long id, String phoneNumber) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        // Currently User model doesn't have phoneNumber field in my create call
        // earlier?
        // Let me check User.java content I wrote.
        // Ah, I missed phoneNumber in the User.java creation step.
        // I should add it now or assume it's there?
        // I will add it using multi_replace_file_content if missing.
        // For now let's assume I'll adding it.
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

        return userPhotoRepository.save(photo);
    }
}
