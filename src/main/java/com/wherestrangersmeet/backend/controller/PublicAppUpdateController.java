package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.AppUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/public/app-update")
@RequiredArgsConstructor
public class PublicAppUpdateController {

    private final AppUpdateService appUpdateService;

    @GetMapping
    public ResponseEntity<?> getUpdateStatus(
            @RequestParam String platform,
            @RequestParam String currentVersion) {
        try {
            return ResponseEntity.ok(appUpdateService.getUpdateStatus(platform, currentVersion));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
