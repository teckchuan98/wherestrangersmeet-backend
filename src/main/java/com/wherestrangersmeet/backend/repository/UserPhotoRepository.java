package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.UserPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserPhotoRepository extends JpaRepository<UserPhoto, Long> {
    List<UserPhoto> findByUserId(Long userId);
}
