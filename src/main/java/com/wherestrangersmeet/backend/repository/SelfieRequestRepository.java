package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.SelfieRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SelfieRequestRepository extends JpaRepository<SelfieRequest, Long> {
    Optional<SelfieRequest> findById(Long id);
}
