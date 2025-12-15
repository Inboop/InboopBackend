package com.inboop.backend.meta.entity;

import com.inboop.backend.meta.enums.DeletionRequestStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity to track Meta data deletion requests.
 *
 * META APP REVIEW NOTE:
 * This entity stores deletion request records as required by Meta's User Data Deletion Policy.
 * Meta requires that you:
 * 1. Track all deletion requests with a unique confirmation code
 * 2. Provide a status page URL where users can check deletion status
 * 3. Complete deletion within a reasonable timeframe
 *
 * The confirmation_code is returned to Meta and can be used to check deletion status.
 */
@Entity
@Table(name = "data_deletion_requests", indexes = {
    @Index(name = "idx_deletion_confirmation_code", columnList = "confirmation_code", unique = true),
    @Index(name = "idx_deletion_facebook_user_id", columnList = "facebook_user_id"),
    @Index(name = "idx_deletion_instagram_business_id", columnList = "instagram_business_id"),
    @Index(name = "idx_deletion_status", columnList = "status")
})
public class DataDeletionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique confirmation code returned to Meta.
     * This is a UUID that identifies this specific deletion request.
     * Users can use this code to check the status of their deletion request.
     */
    @Column(name = "confirmation_code", nullable = false, unique = true, length = 36)
    private String confirmationCode;

    /**
     * Facebook user ID from the signed_request payload.
     * This identifies the Facebook account that initiated the deletion.
     * META APP REVIEW NOTE: This is the primary identifier for finding user data to delete.
     */
    @Column(name = "facebook_user_id", nullable = false)
    private String facebookUserId;

    /**
     * Instagram Business Account ID if present in the signed_request.
     * For Instagram API apps, this identifies the specific IG business account.
     * May be null if not provided in the request.
     */
    @Column(name = "instagram_business_id")
    private String instagramBusinessId;

    /**
     * Current status of the deletion request.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeletionRequestStatus status = DeletionRequestStatus.PENDING;

    /**
     * Type of request: DATA_DELETION or DEAUTHORIZE
     */
    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    /**
     * Timestamp when the request was received from Meta.
     */
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    /**
     * Timestamp when deletion was completed.
     * Null until status is COMPLETED.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Number of records deleted (for audit purposes).
     */
    @Column(name = "records_deleted")
    private Integer recordsDeleted = 0;

    /**
     * Error message if deletion failed.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Raw signed_request payload for audit trail.
     * META APP REVIEW NOTE: Storing the original request helps with debugging
     * and provides an audit trail for compliance verification.
     */
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    /**
     * IP address of the request origin (for security audit).
     */
    @Column(name = "request_ip")
    private String requestIp;

    @PrePersist
    protected void onCreate() {
        requestedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfirmationCode() {
        return confirmationCode;
    }

    public void setConfirmationCode(String confirmationCode) {
        this.confirmationCode = confirmationCode;
    }

    public String getFacebookUserId() {
        return facebookUserId;
    }

    public void setFacebookUserId(String facebookUserId) {
        this.facebookUserId = facebookUserId;
    }

    public String getInstagramBusinessId() {
        return instagramBusinessId;
    }

    public void setInstagramBusinessId(String instagramBusinessId) {
        this.instagramBusinessId = instagramBusinessId;
    }

    public DeletionRequestStatus getStatus() {
        return status;
    }

    public void setStatus(DeletionRequestStatus status) {
        this.status = status;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getRecordsDeleted() {
        return recordsDeleted;
    }

    public void setRecordsDeleted(Integer recordsDeleted) {
        this.recordsDeleted = recordsDeleted;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public String getRequestIp() {
        return requestIp;
    }

    public void setRequestIp(String requestIp) {
        this.requestIp = requestIp;
    }
}
