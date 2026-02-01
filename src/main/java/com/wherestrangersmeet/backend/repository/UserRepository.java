package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByFirebaseUid(String firebaseUid);

    Optional<User> findByEmail(String email);

    List<User> findByIsOnlineTrue();

    // Pagination support: find users who are NOT the current user AND have at least
    // one photo
    Page<User> findByFirebaseUidNotAndPhotosIsNotEmpty(String firebaseUid, Pageable pageable);
}
