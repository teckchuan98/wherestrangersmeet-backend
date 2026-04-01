package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.DailyPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyPromptRepository extends JpaRepository<DailyPrompt, Long> {
    Optional<DailyPrompt> findByActiveDate(LocalDate activeDate);

    Optional<DailyPrompt> findTopByOrderByActiveDateDesc();
}
