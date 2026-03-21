package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.AppUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin/app-update")
@RequiredArgsConstructor
public class AdminAppUpdateController {

    private final AppUpdateService appUpdateService;

    @GetMapping
    public ResponseEntity<?> listConfigs() {
        return ResponseEntity.ok(appUpdateService.listConfigs());
    }

    @GetMapping("/{platform}")
    public ResponseEntity<?> getConfig(@PathVariable String platform) {
        try {
            return ResponseEntity.ok(appUpdateService.getAdminConfig(platform));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{platform}")
    public ResponseEntity<?> updateConfig(
            @PathVariable String platform,
            @RequestBody Map<String, Object> payload) {
        try {
            return ResponseEntity.ok(appUpdateService.upsertConfig(
                    platform,
                    (Boolean) payload.get("updateAvailable"),
                    payload.get("latestVersion") != null ? payload.get("latestVersion").toString() : null,
                    (Boolean) payload.get("force"),
                    payload.get("storeUrl") != null ? payload.get("storeUrl").toString() : null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
