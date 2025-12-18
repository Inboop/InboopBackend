package com.inboop.backend.instagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for Instagram integration status check.
 * Provides clear status, reason, and actionable next steps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IntegrationStatusResponse {

    /**
     * Overall integration status.
     */
    public enum Status {
        NOT_CONNECTED,      // User never started OAuth
        CONNECTED_READY,    // Good to use DMs
        BLOCKED,            // Action required
        PENDING             // Waiting / processing
    }

    /**
     * Reason codes for blocked/pending states.
     */
    public enum Reason {
        NO_PAGES_FOUND,         // User has no Facebook Pages
        IG_NOT_LINKED_TO_PAGE,  // Page exists but no IG account linked
        IG_NOT_BUSINESS,        // IG account is personal, not business/creator
        OWNERSHIP_MISMATCH,     // IG connected via Business Manager but user lacks access
        ADMIN_COOLDOWN,         // Meta 7-day wait after becoming Page admin
        MISSING_PERMISSIONS,    // OAuth permissions not granted
        TOKEN_EXPIRED,          // Access token expired
        API_ERROR               // Unexpected API error
    }

    private Status status;
    private Reason reason;
    private String message;
    private Instant retryAt;
    private Map<String, Object> details;
    private List<NextAction> nextActions;

    // Constructors
    public IntegrationStatusResponse() {}

    public IntegrationStatusResponse(Status status) {
        this.status = status;
    }

    // Static factory methods for common scenarios
    public static IntegrationStatusResponse notConnected() {
        IntegrationStatusResponse response = new IntegrationStatusResponse(Status.NOT_CONNECTED);
        response.setMessage("Instagram account not connected. Click Connect to start.");
        response.setNextActions(List.of(
                new NextAction("CONNECT", "Connect Instagram")
        ));
        return response;
    }

    public static IntegrationStatusResponse connectedReady(String instagramUsername, String facebookPageId, String businessName) {
        IntegrationStatusResponse response = new IntegrationStatusResponse(Status.CONNECTED_READY);
        response.setMessage("Instagram account connected and ready to receive DMs.");
        response.setDetails(Map.of(
                "instagramUsername", instagramUsername != null ? instagramUsername : "",
                "facebookPageId", facebookPageId != null ? facebookPageId : "",
                "businessName", businessName != null ? businessName : ""
        ));
        return response;
    }

    public static IntegrationStatusResponse blocked(Reason reason, String message) {
        IntegrationStatusResponse response = new IntegrationStatusResponse(Status.BLOCKED);
        response.setReason(reason);
        response.setMessage(message);
        return response;
    }

    public static IntegrationStatusResponse pending(String message) {
        IntegrationStatusResponse response = new IntegrationStatusResponse(Status.PENDING);
        response.setMessage(message);
        return response;
    }

    // Getters and Setters
    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Reason getReason() {
        return reason;
    }

    public void setReason(Reason reason) {
        this.reason = reason;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getRetryAt() {
        return retryAt;
    }

    public void setRetryAt(Instant retryAt) {
        this.retryAt = retryAt;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    public List<NextAction> getNextActions() {
        return nextActions;
    }

    public void setNextActions(List<NextAction> nextActions) {
        this.nextActions = nextActions;
    }

    /**
     * Represents an actionable next step for the user.
     */
    public static class NextAction {
        private String type;
        private String label;
        private String url;

        public NextAction() {}

        public NextAction(String type, String label) {
            this.type = type;
            this.label = label;
        }

        public NextAction(String type, String label, String url) {
            this.type = type;
            this.label = label;
            this.url = url;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
