package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
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

        log.info("╔═════════════════════════════════════════════════════");
        log.info("║ ⏰ PRESENCE SERVICE CHECK");
        log.info("║ Time: {}", timestamp);
        log.info("║ Threshold: {} (60s ago)", threshold.format(FORMATTER));
        log.info("║ Online Users Found: {}", onlineUsers.size());
        log.info("╠═════════════════════════════════════════════════════");

        int markedOffline = 0;

        for (User user : onlineUsers) {
            LocalDateTime lastActive = user.getLastActive();
            String lastActiveStr = lastActive != null ? lastActive.format(FORMATTER) : "NULL";
            long secondsSinceActive = lastActive != null ?
                ChronoUnit.SECONDS.between(lastActive, LocalDateTime.now()) : -1;

            log.info("║ Checking User {} ({})", user.getId(), user.getName());
            log.info("║   lastActive: {}", lastActiveStr);
            log.info("║   Seconds since active: {}", secondsSinceActive);

            // If lastActive is null or older than 60 seconds, mark as offline
            if (lastActive == null || lastActive.isBefore(threshold)) {
                log.info("║   Decision: ❌ STALE - Marking OFFLINE");

                userService.updateUserStatus(user.getId(), false, "PresenceService-Timeout");
                markedOffline++;
            } else {
                log.info("║   Decision: ✅ ACTIVE - Keeping ONLINE");
            }
            log.info("║ ─────────────────────────────────────────────────");
        }

        log.info("║ Summary: {}/{} users marked offline", markedOffline, onlineUsers.size());
        log.info("╚═════════════════════════════════════════════════════");
    }
}
