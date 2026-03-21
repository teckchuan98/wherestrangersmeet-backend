package com.wherestrangersmeet.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_update_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppUpdateConfig {

    public enum Platform {
        IOS, ANDROID
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 16)
    private Platform platform;

    @Column(name = "update_available", nullable = false)
    private Boolean updateAvailable = false;

    @Column(name = "latest_version", nullable = false, length = 32)
    private String latestVersion;

    @Column(name = "force_update", nullable = false)
    private Boolean forceUpdate = false;

    @Column(name = "store_url", length = 512)
    private String storeUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
