package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.AppUpdateConfig;
import com.wherestrangersmeet.backend.repository.AppUpdateConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class AppUpdateService {

    private final AppUpdateConfigRepository appUpdateConfigRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getUpdateStatus(String platformValue, String currentVersion) {
        AppUpdateConfig config = getConfig(platformValue);
        boolean versionIsOlder = isVersionOlder(currentVersion, config.getLatestVersion());
        boolean updateAvailable = Boolean.TRUE.equals(config.getUpdateAvailable()) && versionIsOlder;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("platform", config.getPlatform().name());
        payload.put("updateAvailable", updateAvailable);
        payload.put("latestVersion", config.getLatestVersion());
        payload.put("force", Boolean.TRUE.equals(config.getForceUpdate()) && updateAvailable);
        payload.put("storeUrl", config.getStoreUrl());
        payload.put("currentVersion", currentVersion == null ? "" : currentVersion);
        return payload;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAdminConfig(String platformValue) {
        AppUpdateConfig config = getConfig(platformValue);
        return toAdminPayload(config);
    }

    @Transactional
    public Map<String, Object> upsertConfig(
            String platformValue,
            Boolean updateAvailable,
            String latestVersion,
            Boolean forceUpdate,
            String storeUrl) {

        AppUpdateConfig.Platform platform = parsePlatform(platformValue);
        if (latestVersion == null || latestVersion.isBlank()) {
            throw new IllegalArgumentException("latestVersion is required");
        }

        AppUpdateConfig config = appUpdateConfigRepository.findByPlatform(platform)
                .orElseGet(() -> {
                    AppUpdateConfig created = new AppUpdateConfig();
                    created.setPlatform(platform);
                    return created;
                });

        config.setUpdateAvailable(Boolean.TRUE.equals(updateAvailable));
        config.setLatestVersion(latestVersion.trim());
        config.setForceUpdate(Boolean.TRUE.equals(forceUpdate));
        config.setStoreUrl(normalizeStoreUrl(storeUrl));

        return toAdminPayload(appUpdateConfigRepository.save(config));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConfigs() {
        return appUpdateConfigRepository.findAll().stream().map(this::toAdminPayload).toList();
    }

    private AppUpdateConfig getConfig(String platformValue) {
        AppUpdateConfig.Platform platform = parsePlatform(platformValue);
        return appUpdateConfigRepository.findByPlatform(platform)
                .orElseThrow(() -> new NoSuchElementException("App update config not found for platform " + platform));
    }

    private AppUpdateConfig.Platform parsePlatform(String platformValue) {
        if (platformValue == null || platformValue.isBlank()) {
            throw new IllegalArgumentException("platform is required");
        }

        try {
            return AppUpdateConfig.Platform.valueOf(platformValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported platform: " + platformValue);
        }
    }

    private String normalizeStoreUrl(String storeUrl) {
        if (storeUrl == null || storeUrl.isBlank()) {
            return null;
        }
        return storeUrl.trim();
    }

    private Map<String, Object> toAdminPayload(AppUpdateConfig config) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("id", config.getId());
        payload.put("platform", config.getPlatform().name());
        payload.put("updateAvailable", config.getUpdateAvailable());
        payload.put("latestVersion", config.getLatestVersion());
        payload.put("force", config.getForceUpdate());
        payload.put("storeUrl", config.getStoreUrl() == null ? "" : config.getStoreUrl());
        payload.put("createdAt", config.getCreatedAt());
        payload.put("updatedAt", config.getUpdatedAt());
        return payload;
    }

    boolean isVersionOlder(String currentVersion, String latestVersion) {
        if (currentVersion == null || currentVersion.isBlank()) {
            return true;
        }
        if (latestVersion == null || latestVersion.isBlank()) {
            return false;
        }

        int[] current = parseVersion(currentVersion);
        int[] latest = parseVersion(latestVersion);
        int maxLen = Math.max(current.length, latest.length);
        for (int i = 0; i < maxLen; i++) {
            int currentPart = i < current.length ? current[i] : 0;
            int latestPart = i < latest.length ? latest[i] : 0;
            if (currentPart < latestPart) {
                return true;
            }
            if (currentPart > latestPart) {
                return false;
            }
        }
        return false;
    }

    private int[] parseVersion(String version) {
        return java.util.Arrays.stream(version.trim().split("[^0-9]+"))
                .filter(part -> !part.isBlank())
                .mapToInt(part -> {
                    try {
                        return Integer.parseInt(part);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .toArray();
    }
}
