package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PresenceService {

    private final UserRepository userRepository;
    private final UserService userService;

    /**
     * Scheduled task that runs every 30 seconds to mark inactive users as offline.
     * Users are considered inactive if they haven't sent a heartbeat in the last 60
     * seconds.
     */
    @Scheduled(fixedRate = 30000) // Run every 30 seconds
    @Transactional
    public void markInactiveUsersOffline() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(60);

        // Find all users currently marked as online
        List<User> onlineUsers = userRepository.findByIsOnlineTrue();

        for (User user : onlineUsers) {
            LocalDateTime lastActive = user.getLastActive();
            // If lastActive is null or older than 60 seconds, mark as offline
            if (lastActive == null || lastActive.isBefore(threshold)) {
                userService.updateUserStatus(user.getId(), false, "PresenceService-Timeout");
            }
        }
    }
}
