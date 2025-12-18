package com.inboop.backend.instagram.service;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.business.entity.Business;
import com.inboop.backend.business.repository.BusinessRepository;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse.NextAction;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse.Reason;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for checking Instagram integration readiness.
 * Performs real-time checks against Facebook Graph API to verify:
 * - Pages are accessible
 * - Instagram Business Account is linked
 * - Permissions are valid
 * - No cooldown periods are active
 */
@Service
public class InstagramIntegrationCheckService {

    private static final Logger log = LoggerFactory.getLogger(InstagramIntegrationCheckService.class);
    private static final String GRAPH_API_BASE = "https://graph.facebook.com/v21.0";

    private final BusinessRepository businessRepository;
    private final RestTemplate restTemplate;

    public InstagramIntegrationCheckService(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Check the integration status for a user.
     * This performs real-time API checks if the user has a connected account.
     */
    public IntegrationStatusResponse checkStatus(User user) {
        log.info("Checking Instagram integration status for user ID: {}", user.getId());

        // Step 1: Check if user has any connected businesses
        List<Business> businesses = businessRepository.findByOwnerId(user.getId());
        List<Business> activeBusinesses = businesses.stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()) && b.getAccessToken() != null)
                .toList();

        if (activeBusinesses.isEmpty()) {
            log.info("User {} has no connected Instagram accounts", user.getId());
            return IntegrationStatusResponse.notConnected();
        }

        // Step 2: Use the first active business (primary account)
        Business business = activeBusinesses.get(0);

        // Step 3: Perform real-time checks
        return performIntegrationChecks(business);
    }

    /**
     * Perform comprehensive integration checks for a business.
     */
    private IntegrationStatusResponse performIntegrationChecks(Business business) {
        String accessToken = business.getAccessToken();

        // Check 1: Verify token is still valid and get pages
        PagesCheckResult pagesResult = checkFacebookPages(accessToken);

        if (pagesResult.error != null) {
            return pagesResult.error;
        }

        if (pagesResult.pages.isEmpty()) {
            return buildNoPagesResponse();
        }

        // Check 2: Verify Instagram Business Account is linked
        InstagramCheckResult igResult = checkInstagramAccount(business, pagesResult.pages, accessToken);

        if (igResult.error != null) {
            return igResult.error;
        }

        // Check 3: Verify messaging permissions (optional, for v2)
        // For now, if we get here, the account is ready

        log.info("Integration check passed for business {}", business.getId());

        return IntegrationStatusResponse.connectedReady(
                business.getInstagramUsername(),
                business.getFacebookPageId(),
                business.getName()
        );
    }

    /**
     * Check Facebook Pages accessibility.
     */
    private PagesCheckResult checkFacebookPages(String accessToken) {
        String url = GRAPH_API_BASE + "/me/accounts?access_token=" + accessToken;

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null) {
                return new PagesCheckResult(buildApiErrorResponse("Empty response from Facebook API"));
            }

            // Check for API errors
            if (body.containsKey("error")) {
                return handleFacebookError(body);
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

            if (data == null || data.isEmpty()) {
                return new PagesCheckResult(List.of());
            }

            return new PagesCheckResult(data);

        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Token expired or invalid: {}", e.getMessage());
            return new PagesCheckResult(buildTokenExpiredResponse());
        } catch (RestClientException e) {
            log.error("Failed to check Facebook Pages: {}", e.getMessage());
            return new PagesCheckResult(buildApiErrorResponse("Failed to connect to Facebook: " + e.getMessage()));
        }
    }

    /**
     * Check Instagram Business Account status.
     */
    private InstagramCheckResult checkInstagramAccount(Business business, List<Map<String, Object>> pages, String accessToken) {
        // Find the page that matches our stored Facebook Page ID
        Optional<Map<String, Object>> matchingPage = pages.stream()
                .filter(p -> business.getFacebookPageId() != null &&
                            business.getFacebookPageId().equals(p.get("id")))
                .findFirst();

        if (matchingPage.isEmpty()) {
            // Page no longer accessible - could be ownership change or permission revoked
            log.warn("Stored Facebook Page {} not found in user's pages", business.getFacebookPageId());
            return new InstagramCheckResult(buildOwnershipMismatchResponse(business));
        }

        // Check if Instagram is still linked to the page
        String pageId = (String) matchingPage.get().get("id");
        String igCheckUrl = GRAPH_API_BASE + "/" + pageId + "?fields=instagram_business_account&access_token=" + accessToken;

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(igCheckUrl, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null || !body.containsKey("instagram_business_account")) {
                log.warn("Instagram not linked to Page {}", pageId);
                return new InstagramCheckResult(buildIgNotLinkedResponse(business));
            }

            Map<String, Object> igAccount = (Map<String, Object>) body.get("instagram_business_account");
            String igId = (String) igAccount.get("id");

            // Verify it matches our stored IG account ID
            if (business.getInstagramBusinessAccountId() != null &&
                !business.getInstagramBusinessAccountId().equals(igId)) {
                log.warn("Instagram account mismatch. Stored: {}, Found: {}",
                        business.getInstagramBusinessAccountId(), igId);
                return new InstagramCheckResult(buildOwnershipMismatchResponse(business));
            }

            return new InstagramCheckResult(); // Success

        } catch (HttpClientErrorException e) {
            log.error("Error checking Instagram account: {}", e.getMessage());
            return handleInstagramCheckError(e, business);
        } catch (RestClientException e) {
            log.error("Failed to check Instagram account: {}", e.getMessage());
            return new InstagramCheckResult(buildApiErrorResponse("Failed to verify Instagram account"));
        }
    }

    /**
     * Handle Facebook API error responses.
     */
    private PagesCheckResult handleFacebookError(Map<String, Object> body) {
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        Integer code = (Integer) error.get("code");
        String message = (String) error.get("message");
        Integer subcode = error.get("error_subcode") != null ? (Integer) error.get("error_subcode") : null;

        log.warn("Facebook API error: code={}, subcode={}, message={}", code, subcode, message);

        // Error code 190: Invalid/expired token
        if (code != null && code == 190) {
            return new PagesCheckResult(buildTokenExpiredResponse());
        }

        // Error code 10: Missing permissions
        if (code != null && code == 10) {
            return new PagesCheckResult(buildMissingPermissionsResponse(message));
        }

        // Error code 200: Permission error
        if (code != null && code == 200) {
            return new PagesCheckResult(buildMissingPermissionsResponse(message));
        }

        // Default error
        return new PagesCheckResult(buildApiErrorResponse(message));
    }

    /**
     * Handle Instagram check errors, including admin cooldown detection.
     */
    private InstagramCheckResult handleInstagramCheckError(HttpClientErrorException e, Business business) {
        String responseBody = e.getResponseBodyAsString();

        // Check for admin cooldown error (error code 100, subcode 33)
        // This happens when trying to access IG account within 7 days of becoming admin
        if (responseBody.contains("33") || responseBody.toLowerCase().contains("cooldown") ||
            responseBody.toLowerCase().contains("7 day")) {
            return new InstagramCheckResult(buildAdminCooldownResponse(business));
        }

        return new InstagramCheckResult(buildApiErrorResponse("Instagram verification failed: " + e.getMessage()));
    }

    // Response builders

    private IntegrationStatusResponse buildNoPagesResponse() {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.NO_PAGES_FOUND,
                "No Facebook Pages found. You need a Facebook Page connected to an Instagram Business account."
        );
        response.setNextActions(List.of(
                new NextAction("LINK", "Create a Facebook Page", "https://www.facebook.com/pages/create"),
                new NextAction("HELP", "Learn how to connect", "https://help.instagram.com/399237934150902")
        ));
        return response;
    }

    private IntegrationStatusResponse buildIgNotLinkedResponse(Business business) {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.IG_NOT_LINKED_TO_PAGE,
                "Your Facebook Page is not connected to an Instagram Business account."
        );
        response.setDetails(Map.of(
                "facebookPageId", business.getFacebookPageId() != null ? business.getFacebookPageId() : "",
                "businessName", business.getName() != null ? business.getName() : ""
        ));
        response.setNextActions(List.of(
                new NextAction("LINK", "Connect Instagram to Page", "https://www.facebook.com/settings/?tab=linked_instagram"),
                new NextAction("HELP", "Learn how to connect", "https://help.instagram.com/399237934150902")
        ));
        return response;
    }

    private IntegrationStatusResponse buildOwnershipMismatchResponse(Business business) {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.OWNERSHIP_MISMATCH,
                "The Instagram account is connected via Business Manager but you don't have direct access. " +
                "Ask the Business Manager admin to grant you access."
        );
        response.setDetails(Map.of(
                "instagramUsername", business.getInstagramUsername() != null ? business.getInstagramUsername() : "",
                "facebookPageId", business.getFacebookPageId() != null ? business.getFacebookPageId() : ""
        ));
        response.setNextActions(List.of(
                new NextAction("RECONNECT", "Try reconnecting", null),
                new NextAction("HELP", "Contact Business Manager admin", null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildAdminCooldownResponse(Business business) {
        // Meta requires a 7-day wait after becoming a Page admin
        Instant retryAt = Instant.now().plus(7, ChronoUnit.DAYS);

        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.ADMIN_COOLDOWN,
                "Meta requires a 7-day wait after becoming a Page admin before you can access Instagram messaging."
        );
        response.setRetryAt(retryAt);
        response.setDetails(Map.of(
                "facebookPageId", business.getFacebookPageId() != null ? business.getFacebookPageId() : "",
                "instagramUsername", business.getInstagramUsername() != null ? business.getInstagramUsername() : ""
        ));
        response.setNextActions(List.of(
                new NextAction("WAIT", "Try again in 7 days", null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildTokenExpiredResponse() {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.TOKEN_EXPIRED,
                "Your Instagram connection has expired. Please reconnect your account."
        );
        response.setNextActions(List.of(
                new NextAction("RECONNECT", "Reconnect Instagram", null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildMissingPermissionsResponse(String message) {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.MISSING_PERMISSIONS,
                "Missing required permissions. Please reconnect and grant all requested permissions."
        );
        response.setDetails(Map.of(
                "apiMessage", message != null ? message : ""
        ));
        response.setNextActions(List.of(
                new NextAction("RECONNECT", "Reconnect with permissions", null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildApiErrorResponse(String message) {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.API_ERROR,
                "An error occurred while checking your integration: " + message
        );
        response.setNextActions(List.of(
                new NextAction("RETRY", "Try again", null),
                new NextAction("RECONNECT", "Reconnect Instagram", null)
        ));
        return response;
    }

    // Helper classes

    private static class PagesCheckResult {
        List<Map<String, Object>> pages;
        IntegrationStatusResponse error;

        PagesCheckResult(List<Map<String, Object>> pages) {
            this.pages = pages;
        }

        PagesCheckResult(IntegrationStatusResponse error) {
            this.error = error;
            this.pages = List.of();
        }
    }

    private static class InstagramCheckResult {
        IntegrationStatusResponse error;

        InstagramCheckResult() {
            // Success case
        }

        InstagramCheckResult(IntegrationStatusResponse error) {
            this.error = error;
        }
    }
}
