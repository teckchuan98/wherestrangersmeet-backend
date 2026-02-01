package com.wherestrangersmeet.backend.service;

import com.wherestrangersmeet.backend.model.MediaFile;
import com.wherestrangersmeet.backend.repository.MediaFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MediaFileService {

    private final MediaFileRepository mediaFileRepository;

    public Optional<MediaFile> findByHash(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return Optional.empty();
        }
        return mediaFileRepository.findByContentHash(contentHash);
    }

    public MediaFile recordIfAbsent(String contentHash, String objectKey) {
        if (contentHash == null || contentHash.isBlank()) {
            return null;
        }
        return mediaFileRepository.findByContentHash(contentHash)
                .orElseGet(() -> mediaFileRepository.save(MediaFile.builder()
                        .contentHash(contentHash)
                        .objectKey(objectKey)
                        .build()));
    }
}
