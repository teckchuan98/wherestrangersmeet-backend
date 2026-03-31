package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.wherestrangersmeet.backend.model.DailyPromptResponse;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.service.DailyPromptService;
import com.wherestrangersmeet.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/daily-prompts")
@RequiredArgsConstructor
public class DailyPromptController {

    private final DailyPromptService dailyPromptService;
    private final UserService userService;

    @GetMapping("/today")
    public ResponseEntity<?> getTodayPrompt(@AuthenticationPrincipal FirebaseToken principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Authentication failed - no valid Firebase token"));
        }

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseGet(() -> userService.createUserIfNew(
                        principal.getUid(),
                        principal.getEmail(),
                        principal.getName(),
                        principal.getPicture()));

        return ResponseEntity.ok(dailyPromptService.getTodayPromptState(user.getId()));
    }

    @PostMapping("/today/response")
    public ResponseEntity<?> submitTodayResponse(
            @AuthenticationPrincipal FirebaseToken principal,
            @RequestBody Map<String, String> request) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Authentication failed - no valid Firebase token"));
        }

        User user = userService.getUserByFirebaseUid(principal.getUid())
                .orElseGet(() -> userService.createUserIfNew(
                        principal.getUid(),
                        principal.getEmail(),
                        principal.getName(),
                        principal.getPicture()));

        try {
            DailyPromptResponse response = dailyPromptService.submitTodayResponse(user.getId(), request.get("answer"));
            return ResponseEntity.ok(Map.of(
                    "id", response.getId(),
                    "answer", response.getAnswerText(),
                    "answeredAt", response.getCreatedAt()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }
}
