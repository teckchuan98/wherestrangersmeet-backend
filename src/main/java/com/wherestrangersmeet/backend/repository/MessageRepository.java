package com.wherestrangersmeet.backend.repository;

import com.wherestrangersmeet.backend.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // Find all messages between two users, ordered by time
    @Query("SELECT m FROM Message m WHERE (m.senderId = :userId1 AND m.receiverId = :userId2) OR (m.senderId = :userId2 AND m.receiverId = :userId1) ORDER BY m.createdAt ASC")
    List<Message> findConversation(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    // Find latest message for each conversation involving the user
    // This is a bit complex in JPA/SQL, simplifying to just fetching all messages
    // involving the user for now
    // Service layer can process them into conversations
    @Query("SELECT m FROM Message m WHERE m.senderId = :userId OR m.receiverId = :userId ORDER BY m.createdAt DESC")
    List<Message> findByUserId(@Param("userId") Long userId);
}
