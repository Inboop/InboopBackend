package com.inboop.backend.meta.enums;

/**
 * Status of a Meta data deletion request.
 *
 * META APP REVIEW NOTE:
 * Meta requires that you track the status of deletion requests.
 * This enum represents the lifecycle of a deletion request from receipt to completion.
 */
public enum DeletionRequestStatus {
    /**
     * Request received but deletion process has not started yet.
     * This is the initial state when Meta sends the callback.
     */
    PENDING,

    /**
     * Deletion is currently being processed.
     * Data is being deleted/anonymized in the database.
     */
    IN_PROGRESS,

    /**
     * All user data has been successfully deleted/anonymized.
     * This is the final successful state.
     */
    COMPLETED,

    /**
     * Deletion failed due to an error.
     * Manual intervention may be required.
     */
    FAILED
}
