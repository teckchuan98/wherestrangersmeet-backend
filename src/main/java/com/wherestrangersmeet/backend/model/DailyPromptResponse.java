package com.wherestrangersmeet.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "daily_prompt_responses", uniqueConstraints = {
        @UniqueConstraint(name = "uk_daily_prompt_user", columnNames = { "daily_prompt_id", "user_id" })
}, indexes = {
        @Index(name = "idx_daily_prompt_responses_user", columnList = "user_id"),
        @Index(name = "idx_daily_prompt_responses_prompt", columnList = "daily_prompt_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyPromptResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_prompt_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    private DailyPrompt dailyPrompt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    private User user;

    @Column(name = "answer_text", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
