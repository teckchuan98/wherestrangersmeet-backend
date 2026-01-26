package com.wherestrangersmeet.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    @GetMapping("/auth/verify")
    public ResponseEntity<Map<String, Object>> verifyToken(@AuthenticationPrincipal FirebaseToken token) {
        Map<String, Object> response = new HashMap<>();

        if (token != null) {
            response.put("valid", true);
            response.put("uid", token.getUid());
            response.put("email", token.getEmail() != null ? token.getEmail() : "");
            response.put("name", token.getName() != null ? token.getName() : "");
            return ResponseEntity.ok(response);
        } else {
            response.put("valid", false);
            return ResponseEntity.status(401).body(response);
        }
    }

    @GetMapping("/user/profile")
    public ResponseEntity<Map<String, Object>> getUserProfile(@AuthenticationPrincipal FirebaseToken token) {
        Map<String, Object> response = new HashMap<>();
        response.put("uid", token.getUid());
        response.put("email", token.getEmail());
        response.put("name", token.getName());
        response.put("picture", token.getPicture());
        response.put("emailVerified", token.isEmailVerified());
        return ResponseEntity.ok(response);
    }
}
