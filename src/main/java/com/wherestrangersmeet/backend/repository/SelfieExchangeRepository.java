package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.SelfieExchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SelfieExchangeRepository extends JpaRepository<SelfieExchange, Long> {

    @Query("SELECT s FROM SelfieExchange s WHERE " +
            "((s.requesterId = :userA AND s.receiverId = :userB) OR (s.requesterId = :userB AND s.receiverId = :userA)) " +
            "AND s.status IN :statuses ORDER BY s.createdAt DESC")
    List<SelfieExchange> findByParticipantsAndStatuses(
            @Param("userA") Long userA,
            @Param("userB") Long userB,
            @Param("statuses") List<SelfieExchange.Status> statuses);

    default Optional<SelfieExchange> findLatestActiveOrRequestedBetween(Long userA, Long userB) {
        List<SelfieExchange> list = findByParticipantsAndStatuses(
                userA,
                userB,
                List.of(SelfieExchange.Status.REQUESTED, SelfieExchange.Status.ACTIVE));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
