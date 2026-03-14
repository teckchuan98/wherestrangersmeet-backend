package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.BannedEmail;
import com.wherestrangersmeet.backend.repository.BannedEmailRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class BannedEmailService {

    private final BannedEmailRepository bannedEmailRepository;

    public BannedEmailService(BannedEmailRepository bannedEmailRepository) {
        this.bannedEmailRepository = bannedEmailRepository;
    }

    public String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isBanned(String email) {
        String normalizedEmail = normalize(email);
        return normalizedEmail != null && !normalizedEmail.isBlank() && bannedEmailRepository.existsByEmail(normalizedEmail);
    }

    public void ensureNotBanned(String email) {
        String normalizedEmail = normalize(email);
        if (normalizedEmail != null && !normalizedEmail.isBlank() && bannedEmailRepository.existsByEmail(normalizedEmail)) {
            throw new BannedEmailException("This account has been removed and cannot be used to create a new account.");
        }
    }

    @Transactional
    public void ban(String email, Long sourceUserId, String reason) {
        String normalizedEmail = normalize(email);
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return;
        }

        if (bannedEmailRepository.existsByEmail(normalizedEmail)) {
            return;
        }

        BannedEmail bannedEmail = new BannedEmail();
        bannedEmail.setEmail(normalizedEmail);
        bannedEmail.setSourceUserId(sourceUserId);
        bannedEmail.setReason(reason);
        bannedEmailRepository.save(bannedEmail);
    }
}
