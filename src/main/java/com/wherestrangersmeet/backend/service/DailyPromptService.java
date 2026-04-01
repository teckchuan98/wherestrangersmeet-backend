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
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    public Optional<DailyPrompt> getCurrentOrLatestPrompt() {
        Optional<DailyPrompt> todayPromptOpt = getTodayPrompt();
        if (todayPromptOpt.isPresent()) {
            return todayPromptOpt;
        }
        return dailyPromptRepository.findTopByOrderByActiveDateDesc();
    }

    @Transactional(readOnly = true)
    public Optional<DailyPromptResponse> getTodayResponse(Long userId) {
        return getTodayPrompt()
                .flatMap(prompt -> dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(), userId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTodayPromptState(Long userId) {
        Map<String, Object> result = new HashMap<>();
        Optional<DailyPrompt> todayPromptOpt = getTodayPrompt();
        Optional<DailyPrompt> currentPromptOpt = getCurrentOrLatestPrompt();
        Optional<DailyPromptResponse> todayResponseOpt = todayPromptOpt
                .flatMap(prompt -> dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(), userId));
        Optional<DailyPromptResponse> currentPromptResponseOpt = currentPromptOpt
                .flatMap(prompt -> dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(), userId));
        Optional<DailyPromptResponse> latestResponseOpt = dailyPromptResponseRepository
                .findLatestByUserId(userId, PageRequest.of(0, 1))
                .stream()
                .findFirst();

        if (todayResponseOpt.isPresent()) {
            DailyPromptResponse response = todayResponseOpt.get();
            DailyPrompt prompt = response.getDailyPrompt();
            result.put("prompt", buildPromptData(prompt));
            result.put("answered", true);
            result.put("canAnswer", false);
            result.put("answer", response.getAnswerText());
            result.put("answeredAt", response.getCreatedAt());
            return result;
        }

        if (currentPromptResponseOpt.isPresent()) {
            DailyPromptResponse response = currentPromptResponseOpt.get();
            DailyPrompt prompt = response.getDailyPrompt();
            result.put("prompt", buildPromptData(prompt));
            result.put("answered", true);
            result.put("canAnswer", todayPromptOpt.isPresent() && !todayResponseOpt.isPresent());
            result.put("answer", response.getAnswerText());
            result.put("answeredAt", response.getCreatedAt());
            return result;
        }

        if (currentPromptOpt.isPresent()) {
            DailyPrompt prompt = currentPromptOpt.get();
            result.put("prompt", buildPromptData(prompt));
            result.put("answered", false);
            result.put("canAnswer", todayPromptOpt.isPresent());
            return result;
        }

        if (latestResponseOpt.isPresent()) {
            DailyPromptResponse response = latestResponseOpt.get();
            DailyPrompt prompt = response.getDailyPrompt();
            result.put("prompt", buildPromptData(prompt));
            result.put("answered", true);
            result.put("canAnswer", false);
            result.put("answer", response.getAnswerText());
            result.put("answeredAt", response.getCreatedAt());
        } else {
            result.put("prompt", null);
            result.put("answered", false);
            result.put("canAnswer", false);
        }
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

        Optional<DailyPrompt> todayPromptOpt = getTodayPrompt();
        Optional<DailyPrompt> currentPromptOpt = getCurrentOrLatestPrompt();
        Optional<DailyPromptResponse> todayResponseOpt = todayPromptOpt
                .flatMap(prompt -> dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(), user.getId()));
        Optional<DailyPromptResponse> currentPromptResponseOpt = currentPromptOpt
                .flatMap(prompt -> dailyPromptResponseRepository.findByDailyPromptIdAndUserId(prompt.getId(), user.getId()));
        Optional<DailyPromptResponse> latestResponseOpt = dailyPromptResponseRepository
                .findLatestByUserId(user.getId(), PageRequest.of(0, 1))
                .stream()
                .findFirst();

        applyPromptState(user, todayPromptOpt, currentPromptOpt, todayResponseOpt, currentPromptResponseOpt, latestResponseOpt);
    }

    @Transactional(readOnly = true)
    public void attachTodayPromptState(List<User> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        List<Long> userIds = users.stream()
                .map(User::getId)
                .collect(Collectors.toList());
        Optional<DailyPrompt> todayPromptOpt = getTodayPrompt();
        Optional<DailyPrompt> currentPromptOpt = getCurrentOrLatestPrompt();
        Map<Long, DailyPromptResponse> todayResponsesByUserId = todayPromptOpt
                .map(prompt -> dailyPromptResponseRepository
                        .findByUserIdsAndActiveDate(userIds, prompt.getActiveDate())
                        .stream()
                        .collect(Collectors.toMap(response -> response.getUser().getId(), response -> response)))
                .orElseGet(Map::of);
        Map<Long, DailyPromptResponse> currentPromptResponsesByUserId = currentPromptOpt
                .map(prompt -> dailyPromptResponseRepository
                        .findByUserIdsAndActiveDate(userIds, prompt.getActiveDate())
                        .stream()
                        .collect(Collectors.toMap(response -> response.getUser().getId(), response -> response)))
                .orElseGet(Map::of);
        Map<Long, DailyPromptResponse> latestResponsesByUserId = new LinkedHashMap<>();
        for (DailyPromptResponse response : dailyPromptResponseRepository.findLatestByUserIds(userIds)) {
            latestResponsesByUserId.putIfAbsent(response.getUser().getId(), response);
        }

        for (User user : users) {
            applyPromptState(
                    user,
                    todayPromptOpt,
                    currentPromptOpt,
                    Optional.ofNullable(todayResponsesByUserId.get(user.getId())),
                    Optional.ofNullable(currentPromptResponsesByUserId.get(user.getId())),
                    Optional.ofNullable(latestResponsesByUserId.get(user.getId())));
        }
    }

    private void applyPromptState(
            User user,
            Optional<DailyPrompt> todayPromptOpt,
            Optional<DailyPrompt> currentPromptOpt,
            Optional<DailyPromptResponse> todayResponseOpt,
            Optional<DailyPromptResponse> currentPromptResponseOpt,
            Optional<DailyPromptResponse> latestResponseOpt) {
        if (todayResponseOpt.isPresent()) {
            DailyPromptResponse response = todayResponseOpt.get();
            DailyPrompt prompt = response.getDailyPrompt();
            user.setTodayPromptId(prompt.getId());
            user.setTodayPromptDate(prompt.getActiveDate());
            user.setTodayPromptText(prompt.getPromptText());
            user.setTodayPromptAnswered(true);
            user.setTodayPromptAnswer(response.getAnswerText());
            user.setTodayPromptAnsweredAt(response.getCreatedAt());
            return;
        }

        if (currentPromptResponseOpt.isPresent()) {
            DailyPromptResponse response = currentPromptResponseOpt.get();
            DailyPrompt prompt = response.getDailyPrompt();
            user.setTodayPromptId(prompt.getId());
            user.setTodayPromptDate(prompt.getActiveDate());
            user.setTodayPromptText(prompt.getPromptText());
            user.setTodayPromptAnswered(true);
            user.setTodayPromptAnswer(response.getAnswerText());
            user.setTodayPromptAnsweredAt(response.getCreatedAt());
            return;
        }

        if (currentPromptOpt.isPresent()) {
            DailyPrompt prompt = currentPromptOpt.get();
            user.setTodayPromptId(prompt.getId());
            user.setTodayPromptDate(prompt.getActiveDate());
            user.setTodayPromptText(prompt.getPromptText());
            user.setTodayPromptAnswered(false);
            user.setTodayPromptAnswer(null);
            user.setTodayPromptAnsweredAt(null);
            return;
        }

        if (latestResponseOpt.isPresent()) {
            DailyPromptResponse response = latestResponseOpt.get();
            DailyPrompt prompt = response.getDailyPrompt();
            user.setTodayPromptId(prompt.getId());
            user.setTodayPromptDate(prompt.getActiveDate());
            user.setTodayPromptText(prompt.getPromptText());
            user.setTodayPromptAnswered(true);
            user.setTodayPromptAnswer(response.getAnswerText());
            user.setTodayPromptAnsweredAt(response.getCreatedAt());
        } else {
            user.setTodayPromptId(null);
            user.setTodayPromptDate(null);
            user.setTodayPromptText(null);
            user.setTodayPromptAnswered(false);
            user.setTodayPromptAnswer(null);
            user.setTodayPromptAnsweredAt(null);
        }
    }

    private Map<String, Object> buildPromptData(DailyPrompt prompt) {
        Map<String, Object> promptData = new HashMap<>();
        promptData.put("id", prompt.getId());
        promptData.put("text", prompt.getPromptText());
        promptData.put("activeDate", prompt.getActiveDate());
        return promptData;
    }
}
