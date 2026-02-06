package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserRepository extends JpaRepository<User, Long> {
    // Note: These methods now filter out soft-deleted users (deletedAt IS NULL)

    @Query("SELECT u FROM User u WHERE u.firebaseUid = :firebaseUid AND u.deletedAt IS NULL")
    Optional<User> findByFirebaseUid(@Param("firebaseUid") String firebaseUid);

    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("SELECT u FROM User u WHERE u.isOnline = true AND u.deletedAt IS NULL")
    List<User> findByIsOnlineTrue();

    boolean existsByPublicId(String publicId);

    // Pagination support: find users who are NOT the current user AND have at least one photo
    @Query("SELECT u FROM User u WHERE u.firebaseUid <> :firebaseUid AND SIZE(u.photos) > 0 AND u.deletedAt IS NULL")
    Page<User> findByFirebaseUidNotAndPhotosIsNotEmpty(@Param("firebaseUid") String firebaseUid, Pageable pageable);
}
