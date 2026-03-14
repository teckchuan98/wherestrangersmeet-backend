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
}
