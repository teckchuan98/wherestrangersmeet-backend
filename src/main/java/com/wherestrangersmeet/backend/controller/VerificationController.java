package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.OpenAIService;
import com.wherestrangersmeet.backend.service.UserService;
import com.wherestrangersmeet.backend.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private UserService userService;

    @Value("${app.verification.photo.blocking:true}")
    private boolean photoVerificationBlocking;

    @Value("${app.verification.voice.blocking:true}")
    private boolean voiceVerificationBlocking;

    // IP Address -> List of timestamps
    private final Map<String, List<Instant>> requestCounts = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_HOUR = 30;

    @PostMapping("/photos")
    public ResponseEntity<Map<String, Object>> verifyPhotos(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestParam("photo1") MultipartFile photo1,
            @RequestParam("photo2") MultipartFile photo2,
            HttpServletRequest request) {

        if (photoVerificationBlocking) {
            ResponseEntity<Map<String, Object>> consentError = requireAiConsentIfAuthenticated(principal);
            if (consentError != null) {
                return consentError;
            }
        } else {
            log.warn("Photo verification is running in NON-BLOCKING mode.");
        }

        log.info("Verification request received from IP: {}", request.getRemoteAddr());
        String clientIp = request.getRemoteAddr();

        if (photoVerificationBlocking && isRateLimited(clientIp)) {
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
                if (!photoVerificationBlocking) {
                    return ResponseEntity.ok(buildNonBlockingPhotoResponse(null, "OpenAI returned null result."));
                }
                return ResponseEntity.internalServerError().body(Map.of("message", "Internal Error: OpenAI Service returned null"));
            }

            if (!photoVerificationBlocking) {
                return ResponseEntity.ok(buildNonBlockingPhotoResponse(result, null));
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Verification failed: {}", e.getMessage(), e);
            if (!photoVerificationBlocking) {
                return ResponseEntity.ok(buildNonBlockingPhotoResponse(null, "Verification service error: " + e.getClass().getSimpleName()));
            }
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
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("name") String name) {

        if (voiceVerificationBlocking) {
            ResponseEntity<Map<String, Object>> consentError = requireAiConsentIfAuthenticated(principal);
            if (consentError != null) {
                return consentError;
            }
        } else {
            log.warn("Voice verification is running in NON-BLOCKING mode.");
        }

        log.info("Voice verification request received from IP: {}", request.getRemoteAddr());
        String clientIp = request.getRemoteAddr();

        if (voiceVerificationBlocking && isRateLimited(clientIp)) {
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

            if (!voiceVerificationBlocking) {
                return ResponseEntity.ok(buildNonBlockingVoiceResponse(transcript, isValid, null));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("valid", isValid);
            response.put("transcript", transcript);
            response.put("message",
                    isValid ? "Verification successful" : "We heard: '" + transcript + "'. Please try again.");

            log.info("Voice verification result: {} (Transcript: '{}')", isValid, transcript);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Voice verification failed", e);
            if (!voiceVerificationBlocking) {
                return ResponseEntity.ok(buildNonBlockingVoiceResponse(null, false, "Voice verification service error: " + e.getClass().getSimpleName()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> buildNonBlockingPhotoResponse(Map<String, Object> aiResult, String bypassReason) {
        Map<String, Object> response = new HashMap<>();
        response.put("valid", true);
        response.put("facesVisible", true);
        response.put("samePerson", true);
        response.put("message", "Photo verification is temporarily non-blocking.");
        response.put("enforced", false);
        if (aiResult != null) {
            response.put("raw", aiResult);
        }
        if (bypassReason != null && !bypassReason.isBlank()) {
            response.put("bypassReason", bypassReason);
        }
        return response;
    }

    private Map<String, Object> buildNonBlockingVoiceResponse(String transcript, boolean rawValid, String bypassReason) {
        Map<String, Object> response = new HashMap<>();
        response.put("valid", true);
        response.put("message", "Voice verification is temporarily non-blocking.");
        response.put("enforced", false);
        response.put("rawValid", rawValid);
        response.put("transcript", transcript != null ? transcript : "");
        if (bypassReason != null && !bypassReason.isBlank()) {
            response.put("bypassReason", bypassReason);
        }
        return response;
    }

    /**
     * POST /api/verification/selfie
     * Validate a single selfie image contains a human face.
     */
    @PostMapping("/selfie")
    public ResponseEntity<?> verifySelfie(
            HttpServletRequest request,
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestParam("photo") MultipartFile photo) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ResponseEntity<Map<String, Object>> consentError = requireAiConsentIfAuthenticated(principal);
        if (consentError != null) {
            return consentError;
        }

        log.info("Selfie verification request received from IP: {}", request.getRemoteAddr());
        String clientIp = request.getRemoteAddr();

        if (isRateLimited(clientIp)) {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Too many verification attempts. Please try again later."));
        }

        try {
            String mimeType = photo.getContentType() != null ? photo.getContentType() : "image/jpeg";
            String base64Photo = java.util.Base64.getEncoder().encodeToString(photo.getBytes());

            Map<String, Object> aiResult = openAIService.verifyPhotoBase64(
                    base64Photo,
                    mimeType,
                    java.util.Collections.emptyList()
            );

            boolean valid = false;
            Object validObj = aiResult.get("valid");
            if (validObj instanceof Boolean b) {
                valid = b;
            }

            // Backward compatibility with different key names returned by prompts.
            if (!valid) {
                Object faceVisibleObj = aiResult.get("faceVisible");
                if (faceVisibleObj == null) {
                    faceVisibleObj = aiResult.get("facesVisible");
                }
                if (faceVisibleObj instanceof Boolean b) {
                    valid = b;
                }
            }

            String message = valid
                    ? "Selfie validated"
                    : "This is not a selfie. Please capture a photo with your face clearly visible.";

            Map<String, Object> response = new HashMap<>();
            response.put("valid", valid);
            response.put("message", message);
            response.put("raw", aiResult);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Selfie verification failed", e);
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

    private ResponseEntity<Map<String, Object>> requireAiConsentIfAuthenticated(FirebaseToken principal) {
        if (principal == null) {
            return null;
        }

        User user = userService.createUserIfNew(
                principal.getUid(),
                principal.getEmail(),
                principal.getName(),
                principal.getPicture());

        if (userService.hasAcceptedAiConsent(user.getId())) {
            return null;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("error", "AI_CONSENT_REQUIRED");
        payload.put("message", "Please review and accept AI consent before using AI verification.");
        payload.put("currentVersion", userService.getCurrentAiConsentVersion());
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(payload);
    }
}
