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
    private Actions actions;
    private ApiError apiError;

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

    public Actions getActions() {
        return actions;
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    public ApiError getApiError() {
        return apiError;
    }

    public void setApiError(ApiError apiError) {
        this.apiError = apiError;
    }

    /**
     * Raw Meta Graph API error details.
     * Included for debugging/transparency when API calls fail.
     */
    public static class ApiError {
        private Integer code;
        private Integer subcode;
        private String type;
        private String message;
        private String fbtraceId;

        public ApiError() {}

        public ApiError(Integer code, Integer subcode, String type, String message, String fbtraceId) {
            this.code = code;
            this.subcode = subcode;
            this.type = type;
            this.message = message;
            this.fbtraceId = fbtraceId;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public Integer getSubcode() {
            return subcode;
        }

        public void setSubcode(Integer subcode) {
            this.subcode = subcode;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getFbtraceId() {
            return fbtraceId;
        }

        public void setFbtraceId(String fbtraceId) {
            this.fbtraceId = fbtraceId;
        }
    }

    /**
     * Action URLs for the frontend to use.
     * Centralized here so frontend doesn't need to hardcode URLs.
     */
    public static class Actions {
        private String reconnectUrl;
        private String businessSettingsUrl;
        private String businessSuiteUrl;
        private String pageCreateUrl;

        public Actions() {}

        public Actions(String reconnectUrl, String businessSettingsUrl, String businessSuiteUrl, String pageCreateUrl) {
            this.reconnectUrl = reconnectUrl;
            this.businessSettingsUrl = businessSettingsUrl;
            this.businessSuiteUrl = businessSuiteUrl;
            this.pageCreateUrl = pageCreateUrl;
        }

        public String getReconnectUrl() {
            return reconnectUrl;
        }

        public void setReconnectUrl(String reconnectUrl) {
            this.reconnectUrl = reconnectUrl;
        }

        public String getBusinessSettingsUrl() {
            return businessSettingsUrl;
        }

        public void setBusinessSettingsUrl(String businessSettingsUrl) {
            this.businessSettingsUrl = businessSettingsUrl;
        }

        public String getBusinessSuiteUrl() {
            return businessSuiteUrl;
        }

        public void setBusinessSuiteUrl(String businessSuiteUrl) {
            this.businessSuiteUrl = businessSuiteUrl;
        }

        public String getPageCreateUrl() {
            return pageCreateUrl;
        }

        public void setPageCreateUrl(String pageCreateUrl) {
            this.pageCreateUrl = pageCreateUrl;
        }
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
