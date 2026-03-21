package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.AppUpdateConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUpdateConfigRepository extends JpaRepository<AppUpdateConfig, Long> {
    Optional<AppUpdateConfig> findByPlatform(AppUpdateConfig.Platform platform);
}
