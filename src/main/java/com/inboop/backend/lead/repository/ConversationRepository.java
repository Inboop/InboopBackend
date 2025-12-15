package com.inboop.backend.lead.repository;

import com.inboop.backend.lead.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByInstagramConversationId(String instagramConversationId);

    List<Conversation> findByBusinessId(Long businessId);

    /**
     * Find all conversations for a specific Instagram Business Account.
     * Used for data deletion - we traverse Business -> Conversations.
     */
    @Query("SELECT c FROM Conversation c WHERE c.business.instagramBusinessId = :instagramBusinessId")
    List<Conversation> findAllByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);

    /**
     * Get IDs of all conversations for a business.
     * Used for efficient bulk deletion of messages.
     */
    @Query("SELECT c.id FROM Conversation c WHERE c.business.instagramBusinessId = :instagramBusinessId")
    List<Long> findIdsByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);

    /**
     * Delete all conversations for a specific Instagram Business Account.
     *
     * META APP REVIEW NOTE:
     * Conversations contain customer PII (handle, name, profile picture) and
     * must be deleted when the business requests data deletion.
     */
    @Modifying
    @Query("DELETE FROM Conversation c WHERE c.business.instagramBusinessId = :instagramBusinessId")
    int deleteByBusinessInstagramBusinessId(@Param("instagramBusinessId") String instagramBusinessId);
}
