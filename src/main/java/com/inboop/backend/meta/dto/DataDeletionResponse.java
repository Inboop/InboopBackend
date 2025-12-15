package com.inboop.backend.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response format required by Meta for data deletion callbacks.
 *
 * META APP REVIEW NOTE:
 * Meta requires a specific JSON response format for data deletion callbacks:
 * {
 *   "url": "https://example.com/deletion-status?id=abc123",
 *   "confirmation_code": "abc123"
 * }
 *
 * - url: A publicly accessible URL where users can check their deletion status
 * - confirmation_code: A unique identifier for this deletion request
 *
 * @see <a href="https://developers.facebook.com/docs/development/create-an-app/app-dashboard/data-deletion-callback">Meta Data Deletion Callback Documentation</a>
 */
public class DataDeletionResponse {

    /**
     * URL where users can check their deletion status.
     * Must be a publicly accessible URL.
     * Meta will display this URL to users in their Facebook/Instagram settings.
     */
    @JsonProperty("url")
    private String url;

    /**
     * Unique confirmation code for this deletion request.
     * Users can use this code to track their request.
     */
    @JsonProperty("confirmation_code")
    private String confirmationCode;

    public DataDeletionResponse() {
    }

    public DataDeletionResponse(String url, String confirmationCode) {
        this.url = url;
        this.confirmationCode = confirmationCode;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getConfirmationCode() {
        return confirmationCode;
    }

    public void setConfirmationCode(String confirmationCode) {
        this.confirmationCode = confirmationCode;
    }
}
