package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.OpenAIService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/verification")
public class VerificationController {

    private static final Logger log = LoggerFactory.getLogger(VerificationController.class);

    @Autowired
    private OpenAIService openAIService;

    // IP Address -> List of timestamps
    private final Map<String, List<Instant>> requestCounts = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_HOUR = 30;

    @PostMapping("/photos")
    public ResponseEntity<Map<String, Object>> verifyPhotos(
            @RequestParam("photo1") MultipartFile photo1,
            @RequestParam("photo2") MultipartFile photo2,
            HttpServletRequest request) {

        log.info("Verification request received from IP: {}", request.getRemoteAddr());
        String clientIp = request.getRemoteAddr();

        if (isRateLimited(clientIp)) {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Too many verification attempts (" + MAX_REQUESTS_PER_HOUR
                            + "/hour). Please try again later."));
        }

        try {
            log.debug("Calling OpenAIService for photo verification");
            Map<String, Object> result = openAIService.verifyPhotos(photo1, photo2);
            log.debug("OpenAIService returned result: {}", result);

            if (result == null) {
                log.error("OpenAI Service returned null");
                return ResponseEntity.internalServerError()
                        .body(Map.of("message", "Internal Error: OpenAI Service returned null"));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Verification failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Verification failed: " + e.getMessage()));
        }
    }

    private boolean isRateLimited(String ip) {
        Instant now = Instant.now();
        requestCounts.computeIfAbsent(ip, k -> new ArrayList<>());

        List<Instant> timestamps = requestCounts.get(ip);

        synchronized (timestamps) {
            // Remove timestamps older than 1 hour
            timestamps.removeIf(t -> t.isBefore(now.minus(1, ChronoUnit.HOURS)));

            if (timestamps.size() >= MAX_REQUESTS_PER_HOUR) {
                return true;
            }

            timestamps.add(now);
            return false;
        }
    }
}
