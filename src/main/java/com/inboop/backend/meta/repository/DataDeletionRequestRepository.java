package com.inboop.backend.meta.repository;

import com.inboop.backend.meta.entity.DataDeletionRequest;
import com.inboop.backend.meta.enums.DeletionRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing data deletion requests.
 *
 * META APP REVIEW NOTE:
 * This repository provides methods to:
 * 1. Look up deletion requests by confirmation code (for status checks)
 * 2. Find existing requests by user ID (for idempotency)
 * 3. Query pending requests for batch processing
 */
@Repository
public interface DataDeletionRequestRepository extends JpaRepository<DataDeletionRequest, Long> {

    /**
     * Find a deletion request by its confirmation code.
     * Used for the status check endpoint that Meta requires.
     */
    Optional<DataDeletionRequest> findByConfirmationCode(String confirmationCode);

    /**
     * Find existing requests for a Facebook user.
     * Used to check for duplicate deletion requests (idempotency).
     */
    List<DataDeletionRequest> findByFacebookUserId(String facebookUserId);

    /**
     * Find existing requests for an Instagram business account.
     */
    List<DataDeletionRequest> findByInstagramBusinessId(String instagramBusinessId);

    /**
     * Find requests by status.
     * Useful for finding pending requests that need processing.
     */
    List<DataDeletionRequest> findByStatus(DeletionRequestStatus status);

    /**
     * Check if a deletion request already exists for this user and is not yet completed.
     * Used for idempotency - if a pending/in-progress request exists, we return its confirmation code
     * instead of creating a new one.
     */
    Optional<DataDeletionRequest> findByFacebookUserIdAndStatusIn(
            String facebookUserId,
            List<DeletionRequestStatus> statuses
    );
}
