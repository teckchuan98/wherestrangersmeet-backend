package com.wherestrangersmeet.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "selfie_exchanges", indexes = {
        @Index(name = "idx_selfie_exchanges_requester_receiver", columnList = "requester_id,receiver_id"),
        @Index(name = "idx_selfie_exchanges_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SelfieExchange {

    public enum Status {
        REQUESTED,
        ACTIVE,
        COMPLETED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.REQUESTED;

    @Column(name = "requester_photo_key", columnDefinition = "TEXT")
    private String requesterPhotoKey;

    @Column(name = "receiver_photo_key", columnDefinition = "TEXT")
    private String receiverPhotoKey;

    @Column(name = "requester_photo_hash")
    private String requesterPhotoHash;

    @Column(name = "receiver_photo_hash")
    private String receiverPhotoHash;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
