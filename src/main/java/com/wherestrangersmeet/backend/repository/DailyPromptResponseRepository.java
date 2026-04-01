package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.DailyPromptResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyPromptResponseRepository extends JpaRepository<DailyPromptResponse, Long> {
    Optional<DailyPromptResponse> findByDailyPromptIdAndUserId(Long dailyPromptId, Long userId);

    @Query("""
            SELECT r FROM DailyPromptResponse r
            WHERE r.user.id = :userId
            ORDER BY r.dailyPrompt.activeDate DESC, r.createdAt DESC
            """)
    List<DailyPromptResponse> findLatestByUserId(
            @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT r FROM DailyPromptResponse r
            WHERE r.user.id IN :userIds
              AND r.dailyPrompt.activeDate = :activeDate
            """)
    List<DailyPromptResponse> findByUserIdsAndActiveDate(
            @Param("userIds") List<Long> userIds,
            @Param("activeDate") LocalDate activeDate);

    @Query("""
            SELECT r FROM DailyPromptResponse r
            WHERE r.user.id IN :userIds
            ORDER BY r.user.id ASC, r.dailyPrompt.activeDate DESC, r.createdAt DESC
            """)
    List<DailyPromptResponse> findLatestByUserIds(@Param("userIds") List<Long> userIds);
}
