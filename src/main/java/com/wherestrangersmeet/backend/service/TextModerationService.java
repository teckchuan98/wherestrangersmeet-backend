package com.wherestrangersmeet.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class TextModerationService {

    private static final Logger log = LoggerFactory.getLogger(TextModerationService.class);
    private static final String BLOCKED_TERMS_RESOURCE = "moderation/blocked-terms.txt";

    private final Set<String> blockedTerms = new LinkedHashSet<>();

    @PostConstruct
    void loadBlockedTerms() {
        blockedTerms.clear();

        ClassPathResource resource = new ClassPathResource(BLOCKED_TERMS_RESOURCE);
        if (!resource.exists()) {
            log.warn("Blocked terms resource not found: {}", BLOCKED_TERMS_RESOURCE);
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalize(line);
                if (!normalized.isBlank() && !normalized.startsWith("#")) {
                    blockedTerms.add(normalized);
                }
            }
            log.info("Loaded {} blocked text moderation terms", blockedTerms.size());
        } catch (Exception e) {
            log.error("Failed to load blocked terms from {}", BLOCKED_TERMS_RESOURCE, e);
        }
    }

    public boolean containsBlockedContent(String text) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return false;
        }

        for (String blockedTerm : blockedTerms) {
            if (normalizedText.contains(blockedTerm)) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
