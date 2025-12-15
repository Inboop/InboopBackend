package com.inboop.backend.lead.repository;

import com.inboop.backend.lead.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByInstagramMessageId(String instagramMessageId);

    List<Message> findByConversationId(Long conversationId);

    /**
     * Find all messages for conversations belonging to a specific Instagram Business Account.
     */
    @Query("SELECT m FROM Message m WHERE m.conversation.business.instagramBusinessId = :instagramBusinessId")
    List<Message> findAllByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);

    /**
     * Count messages for a business.
     * Used for reporting how many records were deleted.
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversation.business.instagramBusinessId = :instagramBusinessId")
    long countByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);

    /**
     * Delete all messages for conversations belonging to a specific Instagram Business Account.
     *
     * META APP REVIEW NOTE:
     * Messages contain the actual DM content (original_text, translated_text) which is
     * the most sensitive PII. These MUST be deleted when the business requests data deletion.
     * Attachment URLs (which may point to user-uploaded content) are also deleted.
     */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN " +
           "(SELECT c.id FROM Conversation c WHERE c.business.instagramBusinessId = :instagramBusinessId)")
    int deleteByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);

    /**
     * Delete messages by conversation IDs.
     * Used when we already have the conversation IDs (more efficient).
     */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN :conversationIds")
    int deleteByConversationIds(@Param("conversationIds") List<Long> conversationIds);
}
