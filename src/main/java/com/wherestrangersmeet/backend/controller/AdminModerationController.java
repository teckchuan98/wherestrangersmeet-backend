package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.AdminModerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/moderation")
public class AdminModerationController {

    private final AdminModerationService adminModerationService;

    public AdminModerationController(AdminModerationService adminModerationService) {
        this.adminModerationService = adminModerationService;
    }

    @GetMapping("/reports")
    public ResponseEntity<?> listReports() {
        return ResponseEntity.ok(adminModerationService.listReports());
    }

    @GetMapping("/reports/{reportId}")
    public ResponseEntity<?> getReport(@PathVariable Long reportId) {
        try {
            return ResponseEntity.ok(adminModerationService.getReport(reportId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reports/{reportId}/eject")
    public ResponseEntity<?> ejectReportedUser(@PathVariable Long reportId) {
        try {
            adminModerationService.ejectReportedUser(reportId);
            return ResponseEntity.ok(Map.of("ejected", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reports/{reportId}/messages/{messageId}/remove")
    public ResponseEntity<?> removeReportedMessage(@PathVariable Long reportId, @PathVariable Long messageId) {
        try {
            adminModerationService.removeReportedMessage(reportId, messageId);
            return ResponseEntity.ok(Map.of("removed", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{userId}/photos/{photoId}")
    public ResponseEntity<?> removeUserPhoto(@PathVariable Long userId, @PathVariable Long photoId) {
        try {
            adminModerationService.removeUserPhoto(userId, photoId);
            return ResponseEntity.ok(Map.of("removed", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/users/{userId}/profile/moderate")
    public ResponseEntity<?> moderateProfile(@PathVariable Long userId, @RequestBody Map<String, String> request) {
        try {
            adminModerationService.moderateProfile(userId, request.get("action"));
            return ResponseEntity.ok(Map.of("updated", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
