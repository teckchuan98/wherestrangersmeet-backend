package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PresenceService {

    private final UserRepository userRepository;
    private final UserService userService;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Scheduled task that runs every 30 seconds to mark inactive users as offline.
     * Users are considered inactive if they haven't sent a heartbeat in the last 60 seconds.
     */
    @Scheduled(fixedRate = 30000) // Run every 30 seconds
    @Transactional
    public void markInactiveUsersOffline() {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(60);

        // Find all users currently marked as online
        List<User> onlineUsers = userRepository.findByIsOnlineTrue();

        System.out.println("╔═════════════════════════════════════════════════════");
        System.out.println("║ ⏰ PRESENCE SERVICE CHECK");
        System.out.println("║ Time: " + timestamp);
        System.out.println("║ Threshold: " + threshold.format(FORMATTER) + " (60s ago)");
        System.out.println("║ Online Users Found: " + onlineUsers.size());
        System.out.println("╠═════════════════════════════════════════════════════");

        int markedOffline = 0;

        for (User user : onlineUsers) {
            LocalDateTime lastActive = user.getLastActive();
            String lastActiveStr = lastActive != null ? lastActive.format(FORMATTER) : "NULL";
            long secondsSinceActive = lastActive != null ?
                ChronoUnit.SECONDS.between(lastActive, LocalDateTime.now()) : -1;

            System.out.println("║ Checking User " + user.getId() + " (" + user.getName() + ")");
            System.out.println("║   lastActive: " + lastActiveStr);
            System.out.println("║   Seconds since active: " + secondsSinceActive);

            // If lastActive is null or older than 60 seconds, mark as offline
            if (lastActive == null || lastActive.isBefore(threshold)) {
                System.out.println("║   Decision: ❌ STALE - Marking OFFLINE");

                userService.updateUserStatus(user.getId(), false, "PresenceService-Timeout");
                markedOffline++;
            } else {
                System.out.println("║   Decision: ✅ ACTIVE - Keeping ONLINE");
            }
            System.out.println("║ ─────────────────────────────────────────────────");
        }

        System.out.println("║ Summary: " + markedOffline + "/" + onlineUsers.size() + " users marked offline");
        System.out.println("╚═════════════════════════════════════════════════════");
    }
}
