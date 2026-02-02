package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.SelfieResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SelfieResponseRepository extends JpaRepository<SelfieResponse, Long> {
    List<SelfieResponse> findByRequestId(Long requestId);
    Optional<SelfieResponse> findByRequestIdAndUserId(Long requestId, Long userId);
    void deleteByRequestId(Long requestId);
}
