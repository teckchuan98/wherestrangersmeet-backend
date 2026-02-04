package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.OpenAIService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.HashMap;
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

    /**
     * POST /api/verification/voice
     * Verify that the audio matches the expected text: "Hi, I am {Name}"
     */
    @PostMapping("/voice")
    public ResponseEntity<?> verifyVoice(
            HttpServletRequest request,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("name") String name) {

        log.info("Voice verification request received from IP: {}", request.getRemoteAddr());
        String clientIp = request.getRemoteAddr();

        if (isRateLimited(clientIp)) {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Too many verification attempts. Please try again later."));
        }

        try {
            log.info("Processing voice verification for: {}", name);

            // 1. Transcribe audio using Whisper
            String transcript = openAIService.transcribeAudio(audio);

            // 2. Normalize and Compare
            boolean isValid = verifyTranscript(transcript, name);

            Map<String, Object> response = new HashMap<>();
            response.put("valid", isValid);
            response.put("transcript", transcript);
            response.put("message",
                    isValid ? "Verification successful" : "We heard: '" + transcript + "'. Please try again.");

            log.info("Voice verification result: {} (Transcript: '{}')", isValid, transcript);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Voice verification failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private boolean verifyTranscript(String transcript, String name) {
        if (transcript == null)
            return false;

        // Normalize strings: lowercase, remove punctuation
        String t = normalize(transcript);
        String n = normalize(name);
        String expected = normalize("Hi I am " + name);
        String expectedAlt = normalize("Hi Im " + name); // Common contraction

        // 1. Direct contains check (Simple)
        if (t.contains(expected) || t.contains(expectedAlt))
            return true;

        // 2. Check key components
        boolean hasGreeting = t.contains("hi") || t.contains("hello") || t.contains("hey");
        boolean hasIntro = t.contains("i am") || t.contains("im") || t.contains("my name is");
        boolean hasName = t.contains(n);

        // Relaxed criteria: Needs name AND (greeting OR intro)
        if (hasName && (hasGreeting || hasIntro))
            return true;

        // 3. Levenshtein / Fuzzy Match
        double similarity = calculateSimilarity(t, expected);
        double similarityAlt = calculateSimilarity(t, expectedAlt);

        log.info("Levenshtein similarity: {} (alt: {}) for '{}' vs '{}'", similarity, similarityAlt, t, expected);

        // Threshold: 0.75 (75% match) allows for small phonetic errors like "Dee" vs
        // "Teck"
        return similarity >= 0.75 || similarityAlt >= 0.75;
    }

    private double calculateSimilarity(String s1, String s2) {
        String longer = s1, shorter = s2;
        if (s1.length() < s2.length()) { // longer should always have greater length
            longer = s2;
            shorter = s1;
        }
        int longerLength = longer.length();
        if (longerLength == 0) {
            return 1.0;
            /* both strings are zero length */ }
        int editDistance = calculateLevenshteinDistance(longer, shorter);
        return (longerLength - editDistance) / (double) longerLength;
    }

    private int calculateLevenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int j = 0; j < costs.length; j++)
            costs[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= s2.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        s1.charAt(i - 1) == s2.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[s2.length()];
    }

    private String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z ]", "").trim().replaceAll("\\s+", " ");
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
