package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.BannedEmail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BannedEmailRepository extends JpaRepository<BannedEmail, Long> {
    boolean existsByEmail(String email);
    Optional<BannedEmail> findByEmail(String email);
}
