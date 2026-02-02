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
    private final com.wherestrangersmeet.backend.repository.MessageRepository messageRepository; // Added for
                                                                                                 // context/vision
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    // Generous token budget to allow context reading, but cap to prevent abuse
    private static final int MAX_INPUT_TOKENS = 10000;

    public AiResponse generateResponse(List<Message> history, Message triggerMessage) {
        try {
            // 1. Analyze for Vision (On-Demand)
            String imageUrl = detectRelevantImage(triggerMessage);

            // 2. Construct Transcript (with Reply Context & Privacy)
            String transcript = constructTranscript(history, triggerMessage);
            String systemPrompt = getSystemPrompt();

            // 3. Prepare Request Body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o-mini");

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));

            if (imageUrl != null) {
                // MULTIMODAL REQUEST
                List<Map<String, Object>> contentList = new ArrayList<>();
                contentList.add(Map.of("type", "text", "text", transcript));
                contentList.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl, "detail", "low"))); // Low
                                                                                                                     // detail
                                                                                                                     // to
                                                                                                                     // save
                                                                                                                     // costs

                messages.add(Map.of("role", "user", "content", contentList));
                requestBody.put("max_tokens", 1500); // Slightly more for vision analysis
            } else {
                // TEXT ONLY REQUEST
                messages.add(Map.of("role", "user", "content", transcript));
                requestBody.put("max_tokens", 1000);
            }

            requestBody.put("messages", messages);

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
        Collections.reverse(recentMessages); // CRITICAL: Ensure chronological order (Oldest -> Newest)

        // Pre-fetch user names to optimize
        Map<Long, String> userNames = new HashMap<>();

        for (Message msg : recentMessages) {
            // CRITICAL: Privacy - Skip Deleted Messages
            if (Boolean.TRUE.equals(msg.getIsDeleted()))
                continue;

            // Estimate tokens
            int msgTokens = (msg.getText() != null ? msg.getText().length() : 0) / 4;

            // Hard cap huge single messages
            String content = msg.getText();
            if (content != null && content.length() > 2000) {
                content = content.substring(0, 2000) + "...(truncated)";
            }

            // Handle Reply Context Injection
            String replyContext = "";
            if (msg.getReplyToId() != null) {
                // 1. Try to find in valid history first
                Optional<Message> parentInHistory = recentMessages.stream()
                        .filter(m -> m.getId().equals(msg.getReplyToId()) && !Boolean.TRUE.equals(m.getIsDeleted()))
                        .findFirst();

                String parentText = null;

                if (parentInHistory.isPresent()) {
                    parentText = parentInHistory.get().getText();
                } else {
                    // 2. Not in recent history? Fetch from DB with NEIGHBORHOOD!
                    Optional<Message> parentDb = messageRepository.findById(msg.getReplyToId());

                    if (parentDb.isPresent() && !Boolean.TRUE.equals(parentDb.get().getIsDeleted())) {
                        Message parent = parentDb.get();
                        parentText = parent.getText();
                        if ("IMAGE".equalsIgnoreCase(parent.getMessageType())) {
                            parentText = "[Sent an Image]";
                        }

                        // CRITICAL: Fetch Neighborhood (Historical Context)
                        // Only do this if we are processing the TRIGGER message (to avoid explosion of
                        // context for every old reply)
                        if (triggerMessage != null && msg.getId().equals(triggerMessage.getId())) {
                            fetchAndInjectHistoricalContext(sb, parent, userNames);
                        }
                    }
                }

                if (parentText != null) {
                    if (parentText.length() > 50)
                        parentText = parentText.substring(0, 50) + "...";
                    replyContext = " (Replying to: \"" + parentText + "\")";
                }
            }

            // Lazy load name
            String name = userNames.computeIfAbsent(msg.getSenderId(),
                    id -> userRepository.findById(id).map(u -> u.getName()).orElse("User " + id));

            // MARK THE TRIGGER
            boolean isTrigger = msg.getId().equals(triggerMessage.getId());
            String marker = isTrigger ? " [REQ]" : "";

            sb.append("[").append(name).append(replyContext).append("]").append(marker).append(": ").append(content)
                    .append("\n");

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
                   - Focus on the user who sent the message marked **[REQ]** (Request).
                   - If [REQ] is empty (just a summon like "@momo"), you MUST analyze the **IMMEDIATE CONVERSATION** (the 3-5 messages right before [REQ]) to understand what is happening.
                   - **CRITICAL**: Do NOT get distracted by older messages (10+ messages ago). Focus on the *current* topic.

                2. **LANGUAGE (DYNAMIC)**:
                   - **Detect the language** of the immediate conversation.
                   - If users are speaking Malay (e.g., "Gila siot", "Apa yg jadi"), reply in **Malay**.
                   - If users are speaking English, reply in **English**.
                   - Match the user's slang/register (casual vs formal) appropriately.

                3. **DEPTH & FACTS**:
                   - If the user asks for "facts", "analysis", or "thoughts", provide a COMPREHENSIVE and DETAILED response.
                   - Do NOT hold back or be overly concise if depth is requested.
                   - Use objective analysis.

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

    // Detects if we should use Vision capabilities
    private String detectRelevantImage(Message trigger) {
        // Case A: User attached image directly to @momo command
        if ("IMAGE".equalsIgnoreCase(trigger.getMessageType()) &&
                trigger.getAttachmentUrl() != null &&
                !trigger.getAttachmentUrl().isEmpty()) {
            return trigger.getAttachmentUrl();
        }

        // Case B: User Replied to an image
        if (trigger.getReplyToId() != null) {
            Optional<Message> parentOpt = messageRepository.findById(trigger.getReplyToId());
            if (parentOpt.isPresent()) {
                Message parent = parentOpt.get();
                // CRITICAL: Respect Privacy - If parent is deleted, we CANNOT see it
                if (!Boolean.TRUE.equals(parent.getIsDeleted()) &&
                        "IMAGE".equalsIgnoreCase(parent.getMessageType()) &&
                        parent.getAttachmentUrl() != null) {
                    return parent.getAttachmentUrl();
                }
            }
        }

        return null; // No relevant image found
    }

    // Fetches 5 messages before and 5 after the target, injecting them as context
    private void fetchAndInjectHistoricalContext(StringBuilder sb, Message targetMsg, Map<Long, String> userNames) {
        try {
            // 1. Fetch Neighborhood
            org.springframework.data.domain.Pageable limit5 = org.springframework.data.domain.PageRequest.of(0, 5);
            List<Message> before = messageRepository.findTop5Before(targetMsg.getSenderId(), targetMsg.getReceiverId(),
                    targetMsg.getId(), limit5);
            List<Message> after = messageRepository.findTop5After(targetMsg.getSenderId(), targetMsg.getReceiverId(),
                    targetMsg.getId(), limit5);

            // 2. Sort Correctly (Oldest -> Newest)
            Collections.reverse(before); // DESC -> ASC

            // 3. Construct Block
            sb.append("\n[HISTORICAL CONTEXT / FOCUS POINT]\n");

            // Before
            for (Message m : before) {
                if (!Boolean.TRUE.equals(m.getIsDeleted()))
                    appendMessageToTranscript(sb, m, userNames, false);
            }
            // Target
            appendMessageToTranscript(sb, targetMsg, userNames, true); // Mark target specially?

            // After
            for (Message m : after) {
                if (!Boolean.TRUE.equals(m.getIsDeleted()))
                    appendMessageToTranscript(sb, m, userNames, false);
            }

            sb.append("[END HISTORY]\n[RESUMING CURRENT CONVERSATION]...\n");

        } catch (Exception e) {
            log.error("Error fetching historical context: ", e);
        }
    }

    private void appendMessageToTranscript(StringBuilder sb, Message msg, Map<Long, String> userNames,
            boolean isFocus) {
        String name = userNames.computeIfAbsent(msg.getSenderId(),
                id -> userRepository.findById(id).map(u -> u.getName()).orElse("User " + id));
        String content = msg.getText();
        if ("IMAGE".equalsIgnoreCase(msg.getMessageType()))
            content = "[Image]";

        sb.append("[").append(name).append("]").append(isFocus ? " [FOCUS]" : "").append(": ").append(content)
                .append("\n");
    }

    public record AiResponse(String type, String text) {
    }
}
