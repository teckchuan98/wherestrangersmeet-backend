package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.model.UserReport;
import com.wherestrangersmeet.backend.repository.MessageRepository;
import com.wherestrangersmeet.backend.repository.UserReportRepository;
import com.wherestrangersmeet.backend.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class AdminModerationService {

    private final UserReportRepository userReportRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    public AdminModerationService(
            UserReportRepository userReportRepository,
            MessageRepository messageRepository,
            UserRepository userRepository,
            UserService userService) {
        this.userReportRepository = userReportRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<AdminReportView> listReports() {
        List<UserReport> reports = userReportRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<AdminReportView> payload = new ArrayList<>();
        for (UserReport report : reports) {
            payload.add(toView(report));
        }
        return payload;
    }

    @Transactional(readOnly = true)
    public AdminReportView getReport(Long reportId) {
        UserReport report = userReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));
        return toView(report);
    }

    @Transactional
    public void ejectReportedUser(Long reportId) {
        UserReport report = userReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));
        User reportedUser = userRepository.findById(report.getReportedUser().getId())
                .orElseThrow(() -> new IllegalArgumentException("Reported user not found"));

        if (reportedUser.getDeletedAt() == null) {
            userService.deleteUser(reportedUser.getId());
        }
    }

    private AdminReportView toView(UserReport report) {
        User reporter = report.getReporterUser();
        User reported = report.getReportedUser();

        List<Message> recentMessages = messageRepository.findConversation(
                reporter.getId(),
                reported.getId(),
                PageRequest.of(0, 20));
        Collections.reverse(recentMessages);

        List<MessageSummary> messageSummaries = recentMessages.stream()
                .map(message -> new MessageSummary(
                        message.getId(),
                        message.getSenderId(),
                        message.getReceiverId(),
                        message.getText(),
                        message.getMessageType(),
                        message.getCreatedAt(),
                        Boolean.TRUE.equals(message.getIsDeleted())))
                .toList();

        return new AdminReportView(
                report.getId(),
                report.getCreatedAt(),
                report.getReason(),
                new UserSummary(
                        reporter.getId(),
                        reporter.getName(),
                        reporter.getEmail(),
                        reporter.getPublicId(),
                        reporter.getDeletedAt() != null),
                new UserSummary(
                        reported.getId(),
                        reported.getName(),
                        reported.getEmail(),
                        reported.getPublicId(),
                        reported.getDeletedAt() != null),
                messageSummaries);
    }

    public record AdminReportView(
            Long id,
            java.time.LocalDateTime createdAt,
            String reason,
            UserSummary reporter,
            UserSummary reportedUser,
            List<MessageSummary> recentConversation) {
    }

    public record UserSummary(
            Long id,
            String name,
            String email,
            String publicId,
            boolean deleted) {
    }

    public record MessageSummary(
            Long id,
            Long senderId,
            Long receiverId,
            String text,
            String messageType,
            java.time.LocalDateTime createdAt,
            boolean deleted) {
    }
}
