package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.Message;
import com.wherestrangersmeet.backend.model.User;
import com.wherestrangersmeet.backend.model.UserReport;
import com.wherestrangersmeet.backend.model.UserPhoto;
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
import java.util.Locale;

@Service
public class AdminModerationService {

    private final UserReportRepository userReportRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final BannedEmailService bannedEmailService;
    private final FileStorageService fileStorageService;

    public AdminModerationService(
            UserReportRepository userReportRepository,
            MessageRepository messageRepository,
            UserRepository userRepository,
            UserService userService,
            BannedEmailService bannedEmailService,
            FileStorageService fileStorageService) {
        this.userReportRepository = userReportRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.bannedEmailService = bannedEmailService;
        this.fileStorageService = fileStorageService;
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
            bannedEmailService.ban(
                    reportedUser.getEmail(),
                    reportedUser.getId(),
                    "Admin moderation eject from report #" + reportId);
            userService.deleteUser(reportedUser.getId());
        }
    }

    @Transactional
    public void removeReportedMessage(Long reportId, Long messageId) {
        UserReport report = userReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found"));
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));

        Long reporterId = report.getReporterUser().getId();
        Long reportedId = report.getReportedUser().getId();
        boolean belongsToConversation =
                (message.getSenderId().equals(reporterId) && message.getReceiverId().equals(reportedId)) ||
                (message.getSenderId().equals(reportedId) && message.getReceiverId().equals(reporterId));

        if (!belongsToConversation) {
            throw new IllegalArgumentException("Message does not belong to this report conversation");
        }

        message.setIsDeleted(true);
        if (message.getAttachmentUrl() != null && !message.getAttachmentUrl().isBlank()) {
            fileStorageService.deleteFile(message.getAttachmentUrl());
            message.setAttachmentUrl(null);
        }
        messageRepository.save(message);
    }

    @Transactional
    public void removeUserPhoto(Long userId, Long photoId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        UserPhoto photo = user.getPhotos().stream()
                .filter(item -> item.getId().equals(photoId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Photo not found"));

        boolean wasAvatar = photo.getUrl() != null && photo.getUrl().equals(user.getAvatarUrl());
        if (photo.getUrl() != null) {
            fileStorageService.deleteFile(photo.getUrl());
        }

        user.getPhotos().remove(photo);
        if (wasAvatar) {
            user.setAvatarUrl(user.getPhotos().isEmpty() ? null : user.getPhotos().get(0).getUrl());
            user.setAvatarCropX(null);
            user.setAvatarCropY(null);
            user.setAvatarCropScale(null);
        }
        userRepository.save(user);
    }

    @Transactional
    public void moderateProfile(Long userId, String action) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        switch (normalizedAction) {
            case "RESET_NAME" -> user.setName("Removed by moderation");
            case "CLEAR_TEXT" -> {
                user.setBio(null);
                user.setFutureGoals(null);
                user.setOccupationTitle(null);
                user.setOccupationDescription(null);
                user.setInstitution(null);
                user.setOccupationYear(null);
            }
            case "CLEAR_INTERESTS" -> user.setInterestTags(new ArrayList<>());
            default -> throw new IllegalArgumentException("Unsupported moderation action");
        }

        userRepository.save(user);
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
                        message.getAttachmentUrl() != null ? fileStorageService.generatePresignedUrl(message.getAttachmentUrl()) : null,
                        message.getCreatedAt(),
                        Boolean.TRUE.equals(message.getIsDeleted())))
                .toList();

        List<PhotoSummary> reportedPhotos = reported.getPhotos().stream()
                .map(photo -> new PhotoSummary(
                        photo.getId(),
                        fileStorageService.generatePresignedUrl(photo.getUrl()),
                        photo.getCreatedAt()))
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
                        reporter.getDeletedAt() != null,
                        reporter.getBio(),
                        reporter.getFutureGoals(),
                        reporter.getOccupationTitle(),
                        reporter.getOccupationDescription(),
                        reporter.getInstitution(),
                        reporter.getOccupationYear(),
                        reporter.getInterestTags(),
                        List.of()),
                new UserSummary(
                        reported.getId(),
                        reported.getName(),
                        reported.getEmail(),
                        reported.getPublicId(),
                        reported.getDeletedAt() != null,
                        reported.getBio(),
                        reported.getFutureGoals(),
                        reported.getOccupationTitle(),
                        reported.getOccupationDescription(),
                        reported.getInstitution(),
                        reported.getOccupationYear(),
                        reported.getInterestTags(),
                        reportedPhotos),
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
            boolean deleted,
            String bio,
            String futureGoals,
            String occupationTitle,
            String occupationDescription,
            String institution,
            String occupationYear,
            List<String> interestTags,
            List<PhotoSummary> photos) {
    }

    public record MessageSummary(
            Long id,
            Long senderId,
            Long receiverId,
            String text,
            String messageType,
            String attachmentUrl,
            java.time.LocalDateTime createdAt,
            boolean deleted) {
    }

    public record PhotoSummary(
            Long id,
            String url,
            java.time.LocalDateTime createdAt) {
    }
}
