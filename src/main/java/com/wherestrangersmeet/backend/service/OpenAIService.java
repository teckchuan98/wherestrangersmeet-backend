package com.wherestrangersmeet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class OpenAIService {

    @Value("${openai.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> verifyPhotos(MultipartFile photo1, MultipartFile photo2) throws IOException {
        System.out.println(">>> OpenAIService.verifyPhotos called.");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("!!! ERROR: OpenAI API Key is missing or empty!");
            throw new RuntimeException("OpenAI API Key is not configured.");
        } else {
            System.out.println(">>> OpenAI API Key present (length: " + apiKey.length() + ")");
        }

        String base64Image1 = Base64.getEncoder().encodeToString(photo1.getBytes());
        String base64Image2 = Base64.getEncoder().encodeToString(photo2.getBytes());

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
        imageUrl1.put("url", "data:image/jpeg;base64," + base64Image1);
        image1Content.put("image_url", imageUrl1);
        content.add(image1Content);

        // Image 2
        Map<String, Object> image2Content = new HashMap<>();
        image2Content.put("type", "image_url");
        Map<String, String> imageUrl2 = new HashMap<>();
        imageUrl2.put("url", "data:image/jpeg;base64," + base64Image2);
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
            System.out.println("Sending request to OpenAI...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            System.out.println("OpenAI Status: " + response.getStatusCode());
            System.out.println("OpenAI Body: " + response.getBody());

            JsonNode root = objectMapper.readTree(response.getBody());
            String contentString = root.path("choices").get(0).path("message").path("content").asText();
            System.out.println("OpenAI Parsed Content: " + contentString);

            return objectMapper.readValue(contentString, Map.class);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "Error verifying photos: " + e.getMessage());
            return error;
        }
    }

    public Map<String, Object> verifyPhotoUrl(String newPhotoUrl, List<String> referencePhotoUrls) {
        System.out.println(">>> OpenAIService.verifyPhotoUrl called.");
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
            System.out.println("Sending verification request to OpenAI...");
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String contentString = root.path("choices").get(0).path("message").path("content").asText();
            System.out.println("OpenAI Verification Result: " + contentString);

            return objectMapper.readValue(contentString, Map.class);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "Error calling AI service: " + e.getMessage());
            return error;
        }
    }
}
