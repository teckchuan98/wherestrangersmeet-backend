package com.wherestrangersmeet.backend.controller;

import com.wherestrangersmeet.backend.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/verification")
public class VerificationController {

    @Autowired
    private OpenAIService openAIService;

    @PostMapping("/photos")
    public ResponseEntity<Map<String, Object>> verifyPhotos(
            @RequestParam("photo1") MultipartFile photo1,
            @RequestParam("photo2") MultipartFile photo2) {
        System.out.println(">>> Verification Controller HIT!");
        try {
            Map<String, Object> result = openAIService.verifyPhotos(photo1, photo2);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Verification failed: " + e.getMessage()));
        }
    }
}
