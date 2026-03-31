package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.DailyPrompt;
import com.wherestrangersmeet.backend.model.DailyPromptResponse;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.repository.DailyPromptRepository;
import com.wherestrangersmeet.backend.repository.DailyPromptResponseRepository;
import com.wherestrangersmeet.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DailyPromptService {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Singapore");
    private static final int MAX_ANSWER_LENGTH = 1000;

    private final DailyPromptRepository dailyPromptRepository;
    private final DailyPromptResponseRepository dailyPromptResponseRepository;
    private final UserRepository userRepository;

    public LocalDate today() {
        return LocalDate.now(APP_ZONE);
    }

    @Transactional(readOnly = true)
    public Optional<DailyPrompt> getTodayPrompt() {
        return dailyPromptRepository.findByActiveDate(today());
    }

    @Transactional(readOnly = true)
    public Optional<DailyPromptResponse> getTodayResponse(Long userId) {
        return getTodayPrompt()
                .flatMap(prompt -> dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(), userId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTodayPromptState(Long userId) {
        Optional<DailyPrompt> promptOpt = getTodayPrompt();
        if (promptOpt.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("prompt", null);
            result.put("answered", false);
            result.put("canAnswer", false);
            return result;
        }

        DailyPrompt prompt = promptOpt.get();
        Optional<DailyPromptResponse> responseOpt = dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(),
                userId);

        Map<String, Object> promptData = new HashMap<>();
        promptData.put("id", prompt.getId());
        promptData.put("text", prompt.getPromptText());
        promptData.put("activeDate", prompt.getActiveDate());

        Map<String, Object> result = new HashMap<>();
        result.put("prompt", promptData);
        result.put("answered", responseOpt.isPresent());
        result.put("canAnswer", responseOpt.isEmpty());
        responseOpt.ifPresent(response -> {
            result.put("answer", response.getAnswerText());
            result.put("answeredAt", response.getCreatedAt());
        });
        return result;
    }

    @Transactional
    public DailyPromptResponse submitTodayResponse(Long userId, String answerText) {
        String normalized = answerText == null ? "" : answerText.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Answer is required");
        }
        if (normalized.length() > MAX_ANSWER_LENGTH) {
            throw new IllegalArgumentException("Answer must be at most " + MAX_ANSWER_LENGTH + " characters");
        }

        DailyPrompt prompt = getTodayPrompt()
                .orElseThrow(() -> new IllegalStateException("No active prompt today"));

        if (dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(), userId).isPresent()) {
            throw new IllegalStateException("You have already answered today's prompt");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        DailyPromptResponse response = new DailyPromptResponse();
        response.setDailyPrompt(prompt);
        response.setUser(user);
        response.setAnswerText(normalized);
        return dailyPromptResponseRepository.save(response);
    }

    @Transactional(readOnly = true)
    public void attachTodayPromptState(User user) {
        if (user == null || user.getId() == null) {
            return;
        }

        Optional<DailyPrompt> promptOpt = getTodayPrompt();
        if (promptOpt.isEmpty()) {
            user.setTodayPromptId(null);
            user.setTodayPromptDate(null);
            user.setTodayPromptText(null);
            user.setTodayPromptAnswered(false);
            user.setTodayPromptAnswer(null);
            user.setTodayPromptAnsweredAt(null);
            return;
        }

        DailyPrompt prompt = promptOpt.get();
        user.setTodayPromptId(prompt.getId());
        user.setTodayPromptDate(prompt.getActiveDate());
        user.setTodayPromptText(prompt.getPromptText());

        Optional<DailyPromptResponse> responseOpt = dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(),
                user.getId());
        user.setTodayPromptAnswered(responseOpt.isPresent());
        user.setTodayPromptAnswer(responseOpt.map(DailyPromptResponse::getAnswerText).orElse(null));
        user.setTodayPromptAnsweredAt(responseOpt.map(DailyPromptResponse::getCreatedAt).orElse(null));
    }

    @Transactional(readOnly = true)
    public void attachTodayPromptState(List<User> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        Optional<DailyPrompt> promptOpt = getTodayPrompt();
        if (promptOpt.isEmpty()) {
            for (User user : users) {
                user.setTodayPromptId(null);
                user.setTodayPromptDate(null);
                user.setTodayPromptText(null);
                user.setTodayPromptAnswered(false);
                user.setTodayPromptAnswer(null);
                user.setTodayPromptAnsweredAt(null);
            }
            return;
        }

        DailyPrompt prompt = promptOpt.get();
        List<Long> userIds = users.stream()
                .map(User::getId)
                .collect(Collectors.toList());
        Map<Long, DailyPromptResponse> responsesByUserId = dailyPromptResponseRepository
                .findByUserIdsAndActiveDate(userIds, prompt.getActiveDate())
                .stream()
                .collect(Collectors.toMap(response -> response.getUser().getId(), response -> response));

        for (User user : users) {
            user.setTodayPromptId(prompt.getId());
            user.setTodayPromptDate(prompt.getActiveDate());
            user.setTodayPromptText(prompt.getPromptText());

            DailyPromptResponse response = responsesByUserId.get(user.getId());
            user.setTodayPromptAnswered(response != null);
            user.setTodayPromptAnswer(response != null ? response.getAnswerText() : null);
            user.setTodayPromptAnsweredAt(response != null ? response.getCreatedAt() : null);
        }
    }
}
