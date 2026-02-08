package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserReportRepository extends JpaRepository<UserReport, Long> {

    Optional<UserReport> findFirstByReporterUserIdAndReportedUserId(Long reporterUserId, Long reportedUserId);

    List<UserReport> findByReporterUserIdOrderByCreatedAtDesc(Long reporterUserId);

    void deleteByReporterUserIdAndReportedUserId(Long reporterUserId, Long reportedUserId);

    @Query("""
            SELECT CASE WHEN COUNT(ur) > 0 THEN true ELSE false END
            FROM UserReport ur
            WHERE (ur.reporterUser.id = :userId1 AND ur.reportedUser.id = :userId2)
               OR (ur.reporterUser.id = :userId2 AND ur.reportedUser.id = :userId1)
            """)
    boolean existsReportBetweenUsers(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    @Query("SELECT ur.reportedUser.id FROM UserReport ur WHERE ur.reporterUser.id = :reporterUserId")
    List<Long> findReportedUserIdsByReporterUserId(@Param("reporterUserId") Long reporterUserId);

    @Query("SELECT ur.reporterUser.id FROM UserReport ur WHERE ur.reportedUser.id = :reportedUserId")
    List<Long> findReporterUserIdsByReportedUserId(@Param("reportedUserId") Long reportedUserId);
}
