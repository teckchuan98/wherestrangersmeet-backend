package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserCache {

    private static final Logger log = LoggerFactory.getLogger(UserCache.class);
    private final UserRepository userRepository;

    /**
     * Get user ID from Firebase UID (cached for 10 minutes)
     * Saves 10-30ms per lookup
     */
    @Cacheable(value = "userIdCache", key = "#firebaseUid")
    public Long getUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(User::getId)
                .orElseThrow(() -> new RuntimeException("User not found: " + firebaseUid));
    }

    /**
     * Get Firebase UID from user ID (cached for 10 minutes)
     * Saves 10-30ms per lookup
     */
    @Cacheable(value = "firebaseUidCache", key = "#userId")
    public String getFirebaseUid(Long userId) {
        return userRepository.findById(userId)
                .map(User::getFirebaseUid)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
    }

    /**
     * Get full user object (cached for 5 minutes for frequently accessed users)
     */
    @Cacheable(value = "userCache", key = "#userId")
    public Optional<User> getUserById(Long userId) {
        return userRepository.findById(userId);
    }

    /**
     * Get user by Firebase UID (cached)
     */
    @Cacheable(value = "userByFirebaseUidCache", key = "#firebaseUid")
    public Optional<User> getUserByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid);
    }

    /**
     * Invalidate cache when user is updated
     */
    @CacheEvict(value = {"userIdCache", "firebaseUidCache", "userCache", "userByFirebaseUidCache"},
                key = "#firebaseUid")
    public void invalidateByFirebaseUid(String firebaseUid) {
        // Cache eviction handled by annotation
    }

    /**
     * Invalidate cache when user is updated
     */
    @CacheEvict(value = {"userIdCache", "firebaseUidCache", "userCache", "userByFirebaseUidCache"},
                key = "#userId")
    public void invalidateByUserId(Long userId) {
        // Cache eviction handled by annotation
    }

    /**
     * Clear all user caches (use sparingly, e.g., during maintenance)
     */
    @CacheEvict(value = {"userIdCache", "firebaseUidCache", "userCache", "userByFirebaseUidCache"},
                allEntries = true)
    public void clearAllCaches() {
        log.info("🗑️ All user caches cleared");
    }
}
