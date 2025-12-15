package com.inboop.backend.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * Response for the deletion status check endpoint.
 *
 * META APP REVIEW NOTE:
 * Users should be able to check the status of their data deletion request
 * using the URL and confirmation code provided in the initial response.
 * This endpoint is linked from the URL returned to Meta.
 */
public class DeletionStatusResponse {

    @JsonProperty("confirmation_code")
    private String confirmationCode;

    @JsonProperty("status")
    private String status;

    @JsonProperty("status_description")
    private String statusDescription;

    @JsonProperty("requested_at")
    private LocalDateTime requestedAt;

    @JsonProperty("completed_at")
    private LocalDateTime completedAt;

    @JsonProperty("records_deleted")
    private Integer recordsDeleted;

    public DeletionStatusResponse() {
    }

    public String getConfirmationCode() {
        return confirmationCode;
    }

    public void setConfirmationCode(String confirmationCode) {
        this.confirmationCode = confirmationCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusDescription() {
        return statusDescription;
    }

    public void setStatusDescription(String statusDescription) {
        this.statusDescription = statusDescription;
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
}
