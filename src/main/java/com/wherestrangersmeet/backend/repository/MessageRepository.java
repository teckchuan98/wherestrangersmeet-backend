package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // Find all messages between two users, ordered by time
    // Find all messages between two users.
    // We order by DESC here so that Page 0 gives the LATEST messages.
    @Query("SELECT m FROM Message m WHERE (m.senderId = :userId1 AND m.receiverId = :userId2) OR (m.senderId = :userId2 AND m.receiverId = :userId1) ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findConversation(@Param("userId1") Long userId1, @Param("userId2") Long userId2,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT m FROM Message m WHERE ((m.senderId = :userId1 AND m.receiverId = :userId2) OR (m.senderId = :userId2 AND m.receiverId = :userId1)) AND (m.createdAt < :beforeCreatedAt OR (m.createdAt = :beforeCreatedAt AND m.id < :beforeId)) ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findConversationBefore(@Param("userId1") Long userId1, @Param("userId2") Long userId2,
            @Param("beforeCreatedAt") java.time.LocalDateTime beforeCreatedAt,
            @Param("beforeId") Long beforeId,
            org.springframework.data.domain.Pageable pageable);

    // Find latest message for each conversation involving the user
    // This is a bit complex in JPA/SQL, simplifying to just fetching all messages
    // involving the user for now
    // Service layer can process them into conversations
    @Query("SELECT m FROM Message m WHERE m.senderId = :userId OR m.receiverId = :userId ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findByUserId(@Param("userId") Long userId);

    List<Message> findBySenderIdAndReceiverIdAndIsReadFalse(Long senderId, Long receiverId);
}
