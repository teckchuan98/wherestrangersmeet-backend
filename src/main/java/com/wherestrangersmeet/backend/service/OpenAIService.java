package com.wherestrangersmeet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class OpenAIService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);

    @Value("${openai.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> verifyPhotos(MultipartFile photo1, MultipartFile photo2) throws IOException {
        log.info("OpenAIService.verifyPhotos called.");
        if (apiKey == null || apiKey.isEmpty()) {
            log.error("ERROR: OpenAI API Key is missing or empty!");
            throw new RuntimeException("OpenAI API Key is not configured.");
        } else {
            log.info("OpenAI API Key present (length: " + apiKey.length() + ")");
        }

        // Check image sizes
        long size1 = photo1.getSize();
        long size2 = photo2.getSize();
        log.info("Image sizes: photo1=" + size1 + " bytes, photo2=" + size2 + " bytes");

        // OpenAI limit is 20MB for vision API, but base64 adds ~33% overhead
        // So limit original images to 10MB to be safe
        final long MAX_SIZE = 10 * 1024 * 1024; // 10MB
        if (size1 > MAX_SIZE || size2 > MAX_SIZE) {
            log.error("ERROR: Image too large. photo1=" + size1 + ", photo2=" + size2);
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "Images too large. Please use photos under 10MB.");
            return error;
        }

        // Retry up to 3 times
        final int MAX_RETRIES = 3;
        Map<String, Object> lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            log.info("Attempt " + attempt + " of " + MAX_RETRIES);

            try {
                Map<String, Object> result = verifyPhotosInternal(photo1, photo2);

                // If successful, return immediately
                if (result != null && Boolean.TRUE.equals(result.get("valid"))) {
                    log.info("Verification successful on attempt {}", attempt);
                    return result;
                }

                // If validation failed (not a technical error), return immediately
                // Example: "faces not visible" or "not same person"
                if (result != null && result.containsKey("facesVisible")) {
                    log.info("Verification completed on attempt {} (validation result: {})", attempt,
                            result.get("valid"));
                    return result;
                }

                // Technical error - store and retry
                lastError = result;
                log.warn("Technical error on attempt {}, will retry", attempt);

            } catch (Exception e) {
                log.warn("Exception on attempt {}: {}", attempt, e.getMessage());
                lastError = new HashMap<>();
                lastError.put("valid", false);
                lastError.put("message", e.getMessage());
            }

            // Wait before retrying (exponential backoff: 1s, 2s, 4s)
            if (attempt < MAX_RETRIES) {
                try {
                    long waitTime = (long) Math.pow(2, attempt - 1) * 1000; // 1s, 2s, 4s
                    log.info("Waiting {}ms before retry", waitTime);
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // All retries failed - return user-friendly error
        log.error("All {} attempts failed for photo verification", MAX_RETRIES);
        Map<String, Object> userFriendlyError = new HashMap<>();
        userFriendlyError.put("valid", false);
        userFriendlyError.put("message",
                "We're having trouble verifying your photos. Please ensure your face is clearly visible and avoid sensitive content. Try retaking the photos or try again later.");
        return userFriendlyError;
    }

    /**
     * Internal method that performs the actual verification (called by retry logic)
     */
    private Map<String, Object> verifyPhotosInternal(MultipartFile photo1, MultipartFile photo2) throws IOException {

        String base64Image1 = Base64.getEncoder().encodeToString(photo1.getBytes());
        String base64Image2 = Base64.getEncoder().encodeToString(photo2.getBytes());

        log.info("Base64 lengths: photo1=" + base64Image1.length() + ", photo2=" + base64Image2.length());

        // Detect MIME types
        String mimeType1 = photo1.getContentType() != null ? photo1.getContentType() : "image/jpeg";
        String mimeType2 = photo2.getContentType() != null ? photo2.getContentType() : "image/jpeg";
        log.info("MIME types: photo1=" + mimeType1 + ", photo2=" + mimeType2);

        String url = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", "gpt-4o");

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");

        List<Map<String, Object>> content = new ArrayList<>();

        // Text Prompt
        Map<String, Object> textContent = new HashMap<>();
        textContent.put("type", "text");
        textContent.put("text",
                "Analyze these two images. 1. Do both images clearly show a human face? 2. Do both images appear to be of the same person? Return ONLY a JSON object: { \"facesVisible\": boolean, \"samePerson\": boolean, \"valid\": boolean, \"message\": string }");
        content.add(textContent);

        // Image 1
        Map<String, Object> image1Content = new HashMap<>();
        image1Content.put("type", "image_url");
        Map<String, String> imageUrl1 = new HashMap<>();
        imageUrl1.put("url", "data:" + mimeType1 + ";base64," + base64Image1);
        image1Content.put("image_url", imageUrl1);
        content.add(image1Content);

        // Image 2
        Map<String, Object> image2Content = new HashMap<>();
        image2Content.put("type", "image_url");
        Map<String, String> imageUrl2 = new HashMap<>();
        imageUrl2.put("url", "data:" + mimeType2 + ";base64," + base64Image2);
        image2Content.put("image_url", imageUrl2);
        content.add(image2Content);

        userMessage.put("content", content);
        messages.add(userMessage);
        payload.put("messages", messages);
        payload.put("max_tokens", 300);

        // Enforce JSON mode
        Map<String, String> format = new HashMap<>();
        format.put("type", "json_object");
        payload.put("response_format", format);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            log.info("Sending request to OpenAI...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("OpenAI Status: " + response.getStatusCode());
            log.info("OpenAI Response Body: " + response.getBody());

            JsonNode root = objectMapper.readTree(response.getBody());

            // Check if choices array exists and has elements
            if (!root.has("choices") || root.path("choices").size() == 0) {
                log.error("OpenAI response has no choices!");
                Map<String, Object> error = new HashMap<>();
                error.put("valid", false);
                error.put("message", "OpenAI returned empty response");
                return error;
            }

            String contentString = root.path("choices").get(0).path("message").path("content").asText();
            log.info("OpenAI Raw Content: " + contentString);

            // Extract JSON from response (handles markdown code blocks and extra text)
            String jsonString = extractJson(contentString);
            log.info("Extracted JSON: " + jsonString);

            return objectMapper.readValue(jsonString, Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // HTTP 4xx errors (bad request, unauthorized, rate limit, etc.)
            log.error("OpenAI HTTP Client Error: " + e.getStatusCode());
            log.error("Response Body: " + e.getResponseBodyAsString());
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "OpenAI API Error (" + e.getStatusCode() + "): " + e.getResponseBodyAsString());
            return error;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // HTTP 5xx errors (OpenAI server error)
            log.error("OpenAI HTTP Server Error: " + e.getStatusCode());
            log.error("Response Body: " + e.getResponseBodyAsString());
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "OpenAI service is temporarily unavailable. Please try again.");
            return error;
        } catch (Exception e) {
            log.error("Unexpected error during photo verification: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "Error verifying photos: " + e.getMessage());
            return error;
        }
    }

    public Map<String, Object> verifyPhotoUrl(String newPhotoUrl, List<String> referencePhotoUrls) {
        log.info("OpenAIService.verifyPhotoUrl called.");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OpenAI API Key is not configured.");
        }

        String url = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", "gpt-4o");

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");

        List<Map<String, Object>> content = new ArrayList<>();

        // Text Prompt
        Map<String, Object> textContent = new HashMap<>();
        textContent.put("type", "text");
        String prompt = "Analyze these images. The first image is the NEW photo. The subsequent images are reference photos of the SAME person.\n"
                +
                "1. Does the NEW photo clearly show a human face?\n" +
                "2. Does the person in the NEW photo look like the same person in the reference photos (if provided)?\n"
                +
                "Return ONLY a JSON object: { \"faceVisible\": boolean, \"samePerson\": boolean, \"valid\": boolean, \"message\": string }.\n"
                +
                "If no reference photos are provided, 'samePerson' should be true (or ignored). 'valid' should be true only if faceVisible is true and samePerson is true (if references exist).";
        textContent.put("text", prompt);
        content.add(textContent);

        // New Photo
        Map<String, Object> newPhotoContent = new HashMap<>();
        newPhotoContent.put("type", "image_url");
        Map<String, String> newPhotoUrlMap = new HashMap<>();
        newPhotoUrlMap.put("url", newPhotoUrl);
        newPhotoContent.put("image_url", newPhotoUrlMap);
        content.add(newPhotoContent);

        // Reference Photos
        if (referencePhotoUrls != null) {
            for (String refUrl : referencePhotoUrls) {
                Map<String, Object> refPhotoContent = new HashMap<>();
                refPhotoContent.put("type", "image_url");
                Map<String, String> refPhotoUrlMap = new HashMap<>();
                refPhotoUrlMap.put("url", refUrl);
                refPhotoContent.put("image_url", refPhotoUrlMap);
                content.add(refPhotoContent);
            }
        }

        userMessage.put("content", content);
        messages.add(userMessage);
        payload.put("messages", messages);
        payload.put("max_tokens", 300);

        // Enforce JSON mode
        Map<String, String> format = new HashMap<>();
        format.put("type", "json_object");
        payload.put("response_format", format);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            log.info("Sending verification request to OpenAI...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("OpenAI Status: " + response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());

            // Check if choices array exists and has elements
            if (!root.has("choices") || root.path("choices").size() == 0) {
                log.error("OpenAI response has no choices!");
                Map<String, Object> error = new HashMap<>();
                error.put("valid", false);
                error.put("message", "OpenAI returned empty response");
                return error;
            }

            String contentString = root.path("choices").get(0).path("message").path("content").asText();
            log.info("OpenAI Raw Content: " + contentString);

            // Extract JSON from response (handles markdown code blocks and extra text)
            String jsonString = extractJson(contentString);
            log.info("Extracted JSON: " + jsonString);

            return objectMapper.readValue(jsonString, Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("OpenAI HTTP Client Error: " + e.getStatusCode());
            log.error("Response Body: " + e.getResponseBodyAsString());
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "OpenAI API Error (" + e.getStatusCode() + "): " + e.getResponseBodyAsString());
            return error;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("OpenAI HTTP Server Error: " + e.getStatusCode());
            log.error("Response Body: " + e.getResponseBodyAsString());
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "OpenAI service is temporarily unavailable. Please try again.");
            return error;
        } catch (Exception e) {
            log.error("Unexpected error during photo URL verification: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "Error calling AI service: " + e.getMessage());
            return error;
        }
    }

    /**
     * Extracts JSON from OpenAI response that may contain markdown code blocks or
     * extra text.
     * Handles cases like:
     * - Plain JSON: {"valid": true, ...}
     * - Markdown: ```json\n{"valid": true, ...}\n```
     * - With text: Here's the result: {"valid": true, ...}
     */
    private String extractJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "{}";
        }

        String trimmed = content.trim();

        // Case 1: Markdown code block with ```json ... ```
        if (trimmed.contains("```json")) {
            int startIndex = trimmed.indexOf("```json") + 7; // Length of "```json"
            int endIndex = trimmed.indexOf("```", startIndex);
            if (endIndex > startIndex) {
                return trimmed.substring(startIndex, endIndex).trim();
            }
        }

        // Case 2: Markdown code block with ``` ... ``` (no language specified)
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            String extracted = trimmed.substring(3, trimmed.length() - 3).trim();
            // Remove language identifier if present (e.g., ```json)
            if (extracted.startsWith("json")) {
                extracted = extracted.substring(4).trim();
            }
            return extracted;
        }

        // Case 3: Find JSON object by looking for { ... }
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }

        // Case 4: Return as-is (might be plain JSON)
        return trimmed;
    }

    public Map<String, Object> verifyPhotoBase64(String base64NewPhoto, String mimeType,
            List<String> referencePhotoUrls) {
        log.info("OpenAIService.verifyPhotoBase64 called.");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OpenAI API Key is not configured.");
        }

        String url = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", "gpt-4o");

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");

        List<Map<String, Object>> content = new ArrayList<>();

        // Text Prompt
        Map<String, Object> textContent = new HashMap<>();
        textContent.put("type", "text");
        String prompt = "Analyze these images. The first image is the NEW photo. The subsequent images are reference photos of the SAME person.\n"
                +
                "1. Does the NEW photo clearly show a human face?\n" +
                "2. Does the person in the NEW photo look like the same person in the reference photos (if provided)?\n"
                +
                "Return ONLY a JSON object: { \"faceVisible\": boolean, \"samePerson\": boolean, \"valid\": boolean, \"message\": string }.\n"
                +
                "If no reference photos are provided, 'samePerson' should be true (or ignored). 'valid' should be true only if faceVisible is true and samePerson is true (if references exist).";
        textContent.put("text", prompt);
        content.add(textContent);

        // New Photo (Base64)
        Map<String, Object> newPhotoContent = new HashMap<>();
        newPhotoContent.put("type", "image_url");
        Map<String, String> newPhotoUrlMap = new HashMap<>();
        newPhotoUrlMap.put("url", "data:" + mimeType + ";base64," + base64NewPhoto);
        newPhotoContent.put("image_url", newPhotoUrlMap);
        content.add(newPhotoContent);

        // Reference Photos
        if (referencePhotoUrls != null) {
            for (String refUrl : referencePhotoUrls) {
                Map<String, Object> refPhotoContent = new HashMap<>();
                refPhotoContent.put("type", "image_url");
                Map<String, String> refPhotoUrlMap = new HashMap<>();
                // Check if refUrl is already a data URI or a regular URL
                refPhotoUrlMap.put("url", refUrl);
                refPhotoContent.put("image_url", refPhotoUrlMap);
                content.add(refPhotoContent);
            }
        }

        userMessage.put("content", content);
        messages.add(userMessage);
        payload.put("messages", messages);
        payload.put("max_tokens", 300);

        // Enforce JSON mode
        Map<String, String> format = new HashMap<>();
        format.put("type", "json_object");
        payload.put("response_format", format);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            log.info("Sending verification request to OpenAI (Base64 Mode)...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("OpenAI Keep-Alive Response: " + response.getStatusCode()); // Logging status

            JsonNode root = objectMapper.readTree(response.getBody());

            if (!root.has("choices") || root.path("choices").size() == 0) {
                Map<String, Object> error = new HashMap<>();
                error.put("valid", false);
                error.put("message", "OpenAI returned empty response");
                return error;
            }

            String contentString = root.path("choices").get(0).path("message").path("content").asText();
            String jsonString = extractJson(contentString);
            return objectMapper.readValue(jsonString, Map.class);
        } catch (Exception e) {
            log.error("Error verifying photo (Base64): {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "Error verifying photo: " + e.getMessage());
            return error;
        }
    }

    public String transcribeAudio(MultipartFile audioFile) throws IOException {
        log.info("OpenAIService.transcribeAudio called. Size: {}", audioFile.getSize());
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OpenAI API Key is not configured.");
        }

        String url = "https://api.openai.com/v1/audio/transcriptions";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        byte[] uploadBytes = audioFile.getBytes();
        String uploadFilename = resolveAudioFilename(audioFile);

        try {
            PreprocessedAudio preprocessed = preprocessAudioForWhisper(audioFile);
            uploadBytes = preprocessed.bytes();
            uploadFilename = preprocessed.filename();
            log.info("Audio preprocessed for Whisper ({} bytes, file={})", uploadBytes.length, uploadFilename);
        } catch (Exception e) {
            // Safe fallback: continue with original audio if ffmpeg is unavailable or processing fails.
            log.warn("Audio preprocessing skipped, falling back to raw input: {}", e.getMessage());
        }

        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        final String finalUploadFilename = uploadFilename;
        body.add("file", new org.springframework.core.io.ByteArrayResource(uploadBytes) {
            @Override
            public String getFilename() {
                // OpenAI requires a filename with extension
                return finalUploadFilename;
            }
        });
        body.add("model", "whisper-1");
        body.add("language", "en"); // Force English for now, or detect

        HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            log.info("Sending audio to Whisper...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("Whisper Status: " + response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("text")) {
                String transcript = root.get("text").asText();
                log.info("Transcript: {}", transcript);
                return transcript;
            } else {
                throw new RuntimeException("No text found in Whisper response");
            }

        } catch (Exception e) {
            log.error("Error transcribing audio: {}", e.getMessage(), e);
            throw new RuntimeException("Transcription failed: " + e.getMessage());
        }
    }

    private record PreprocessedAudio(byte[] bytes, String filename) {
    }

    private String resolveAudioFilename(MultipartFile audioFile) {
        String original = audioFile.getOriginalFilename();
        return (original != null && !original.isBlank()) ? original : "audio.m4a";
    }

    private PreprocessedAudio preprocessAudioForWhisper(MultipartFile audioFile) throws IOException, InterruptedException {
        Path inputPath = null;
        Path outputPath = null;
        try {
            inputPath = Files.createTempFile("wsm-voice-in-", ".m4a");
            outputPath = Files.createTempFile("wsm-voice-out-", ".wav");
            audioFile.transferTo(inputPath.toFile());

            List<String> command = Arrays.asList(
                    "ffmpeg",
                    "-y",
                    "-i", inputPath.toString(),
                    "-vn",
                    "-af", "highpass=f=80,lowpass=f=8000,afftdn=nf=-25,loudnorm",
                    "-ar", "16000",
                    "-ac", "1",
                    outputPath.toString()
            );

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            String ffmpegOutput = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("ffmpeg failed with exit code " + exitCode + ": " + ffmpegOutput);
            }

            byte[] cleanedBytes = Files.readAllBytes(outputPath);
            if (cleanedBytes.length == 0) {
                throw new IOException("ffmpeg produced empty audio output");
            }

            return new PreprocessedAudio(cleanedBytes, "audio-cleaned.wav");
        } finally {
            safeDelete(inputPath);
            safeDelete(outputPath);
        }
    }

    private void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }
}
