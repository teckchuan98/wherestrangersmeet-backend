package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.MediaFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MediaFileRepository extends JpaRepository<MediaFile, Long> {
    Optional<MediaFile> findByContentHash(String contentHash);
}
