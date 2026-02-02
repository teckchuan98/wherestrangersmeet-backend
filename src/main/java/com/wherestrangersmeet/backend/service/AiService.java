package com.wherestrangersmeet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    @Value("${openai.api.key}")
    private String openAiApiKey;

    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    // Generous token budget to allow context reading, but cap to prevent abuse
    private static final int MAX_INPUT_TOKENS = 10000;

    public AiResponse generateResponse(List<Message> history, Message triggerMessage) {
        try {
            String transcript = constructTranscript(history, triggerMessage);
            String systemPrompt = getSystemPrompt();

            // Prepare Request Body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o-mini"); // Fast, cheap, intelligent enough

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", transcript));

            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 1000); // Increased for in-depth analysis

            // Prepare Headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // Execute Call
            ResponseEntity<String> response = restTemplate.postForEntity(OPENAI_URL, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseResponse(response.getBody());
            }

        } catch (Exception e) {
            log.error("Error calling OpenAI: ", e);
        }

        // Fallback
        return new AiResponse("AI_SERIOUS",
                "I'm having trouble connecting to my brain right now. Please try again later.");
    }

    private String constructTranscript(List<Message> history, Message triggerMessage) {
        StringBuilder sb = new StringBuilder();
        int estimatedTokens = 0;

        // Process from Oldest to Newest
        List<Message> recentMessages = new ArrayList<>(history);
        // Collections.reverse(recentMessages); // This line was removed as per
        // instructions

        // Pre-fetch user names to optimize
        Map<Long, String> userNames = new HashMap<>();

        for (Message msg : recentMessages) {
            // Estimate tokens
            int msgTokens = (msg.getText() != null ? msg.getText().length() : 0) / 4;

            // Hard cap huge single messages
            String content = msg.getText();
            if (content != null && content.length() > 2000) {
                content = content.substring(0, 2000) + "...(truncated)";
            }

            // Lazy load name
            String name = userNames.computeIfAbsent(msg.getSenderId(),
                    id -> userRepository.findById(id).map(u -> u.getName()).orElse("User " + id));

            // MARK THE TRIGGER
            boolean isTrigger = msg.getId().equals(triggerMessage.getId());
            String marker = isTrigger ? " [REQ]" : "";

            sb.append("[").append(name).append("]").append(marker).append(": ").append(content).append("\n");

            estimatedTokens += msgTokens;
            if (estimatedTokens > MAX_INPUT_TOKENS)
                break;
        }

        // Add the trigger instructions
        sb.append(
                "\n(SYSTEM: The message marked [REQ] is the one that summoned you. Respond primarily to that user's request, using the other messages as context. If messages appear AFTER [REQ], they are new context/interruption.)");
        return sb.toString();
    }

    private String getSystemPrompt() {
        return """
                You are a wise, knowledgeable, and empathetic third participant in a chat group.
                Your role is to listen, analyze, and offer perspective or facts when asked.

                INSTRUCTIONS:
                1. **ADDRESSING**: You MUST address users by their names provided in the transcript.
                   - Focus on the user who sent the message marked **[REQ]** (Request). That is the person asking you.
                   - If User A sent [REQ] asking for help, address User A explicitly.
                   - Do not get confused if other users sent messages after [REQ].

                2. **DEPTH & FACTS**:
                   - If the user asks for "facts", "analysis", or "thoughts", provide a COMPREHENSIVE and DETAILED response.
                   - Do NOT hold back or be overly concise if depth is requested.
                   - Use objective analysis. You can provide multiple viewpoints.

                3. **TONE**:
                   - Warm, grounded, conversational, but intellectual if the topic demands it.
                   - Act like a smart mutual friend.

                4. **IDENTITY (CRITICAL)**:
                   - If asked who you are, who created you, if you are ChatGPT/OpenAI, etc., you MUST reply with this EXACT phrase: "I am MOMO AI."
                   - Do not add explanations, apologies, or extra text.
                """;
    }

    private AiResponse parseResponse(String jsonBody) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();

            // Clean up any potential hallucinations of tags (just in case)
            content = content.replace("[SERIOUS]", "").replace("[JOKER]", "").trim();

            return new AiResponse("AI_SERIOUS", content); // Always return standard AI type

        } catch (Exception e) {
            log.error("Error parsing AI response: ", e);
            return new AiResponse("AI_SERIOUS", "Beep boop. Something went wrong decoding." + e.getMessage());
        }
    }

    public record AiResponse(String type, String text) {
    }
}
