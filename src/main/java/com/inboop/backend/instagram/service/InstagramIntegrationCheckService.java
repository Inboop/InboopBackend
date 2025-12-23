package com.inboop.backend.instagram.service;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.business.entity.Business;
import com.inboop.backend.business.repository.BusinessRepository;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse.Actions;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse.NextAction;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse.Reason;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse.Status;
import com.inboop.backend.shared.constant.AppConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for checking Instagram integration readiness.
 *
 * Performs real-time checks against Facebook Graph API to verify:
 * - Pages are accessible
 * - Instagram Business Account is linked
 * - Permissions are valid
 * - No cooldown periods are active
 *
 * All responses are deterministic and all checks are logged with structured format.
 * Access tokens are NEVER exposed in logs or responses.
 */
@Service
public class InstagramIntegrationCheckService {

    private static final Logger log = LoggerFactory.getLogger(InstagramIntegrationCheckService.class);
    private static final String GRAPH_API_BASE = "https://graph.facebook.com/v21.0";

    private final BusinessRepository businessRepository;
    private final RestTemplate restTemplate;
    private final String backendBaseUrl;

    public InstagramIntegrationCheckService(
            BusinessRepository businessRepository,
            @Value("${app.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.businessRepository = businessRepository;
        this.restTemplate = new RestTemplate();
        this.backendBaseUrl = backendBaseUrl;
    }

    /**
     * Build the actions object with URLs for frontend.
     * No tokens are included in any URLs - reconnectUrl triggers OAuth flow.
     */
    private Actions buildActions() {
        return new Actions(
                backendBaseUrl + AppConstants.OAUTH_FACEBOOK_PATH,
                AppConstants.META_BUSINESS_SETTINGS_URL,
                AppConstants.META_BUSINESS_SUITE_URL,
                AppConstants.META_PAGE_CREATE_URL
        );
    }

    /**
     * Check the integration status for a user.
     *
     * IMPORTANT: This method is safe to call repeatedly (idempotent for polling).
     * - Returns cached BLOCKED status if cooldown is active (no API call)
     * - Returns CONNECTED_READY from DB if recently verified (no API call)
     * - Only performs real-time API checks when necessary
     */
    public IntegrationStatusResponse checkStatus(User user) {
        log.info("[StatusCheck] Starting for user_id={}", user.getId());

        // Step 1: Check if user has any businesses (connected or with error context)
        List<Business> businesses = businessRepository.findByOwnerId(user.getId());

        if (businesses.isEmpty()) {
            log.info("[StatusCheck] Result: user_id={}, status=NOT_CONNECTED, reason=no_businesses_in_db", user.getId());
            IntegrationStatusResponse response = IntegrationStatusResponse.notConnected();
            response.setActions(buildActions());
            return response;
        }

        // Step 2: Find the primary business (prefer active ones)
        Business business = businesses.stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .findFirst()
                .orElse(businesses.get(0));

        // Step 3: Check for stored cooldown - return immediately without API call
        if (business.getConnectionRetryAt() != null) {
            LocalDateTime retryAt = business.getConnectionRetryAt();
            if (retryAt.isAfter(LocalDateTime.now())) {
                log.info("[StatusCheck] Result: user_id={}, status=BLOCKED, reason=ADMIN_COOLDOWN, retry_at={} (cached)",
                        user.getId(), retryAt);
                IntegrationStatusResponse response = buildAdminCooldownResponse(business, retryAt);
                response.setActions(buildActions());
                return response;
            }
            // Cooldown expired - clear it and continue checking
            clearCooldown(business);
        }

        // Step 4: If no access token, not connected
        if (business.getAccessToken() == null) {
            log.info("[StatusCheck] Result: user_id={}, status=NOT_CONNECTED, reason=no_access_token", user.getId());
            IntegrationStatusResponse response = IntegrationStatusResponse.notConnected();
            response.setActions(buildActions());
            return response;
        }

        // Step 5: If recently verified (within 5 minutes) and was CONNECTED_READY, return cached
        if (canUseCachedStatus(business)) {
            log.info("[StatusCheck] Result: user_id={}, status=CONNECTED_READY (cached), ig_username={}",
                    user.getId(), business.getInstagramUsername());
            IntegrationStatusResponse response = IntegrationStatusResponse.connectedReady(
                    business.getInstagramUsername(),
                    business.getFacebookPageId(),
                    business.getName()
            );
            response.setActions(buildActions());
            return response;
        }

        // Step 6: Perform real-time API checks
        return performIntegrationChecks(user, business);
    }

    /**
     * Check if we can use cached status (avoid unnecessary API calls for polling).
     */
    private boolean canUseCachedStatus(Business business) {
        // Must be active with IG account linked and no errors
        if (!Boolean.TRUE.equals(business.getIsActive())) return false;
        if (business.getInstagramBusinessAccountId() == null) return false;
        if (business.getLastConnectionError() != null) return false;

        // Must have been verified recently (within 5 minutes)
        if (business.getLastStatusCheckAt() == null) return false;
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
        return business.getLastStatusCheckAt().isAfter(fiveMinutesAgo);
    }

    /**
     * Clear expired cooldown (separate transaction to avoid side effects in read path).
     */
    @Transactional
    public void clearCooldown(Business business) {
        business.setConnectionRetryAt(null);
        businessRepository.save(business);
        log.debug("[StatusCheck] Cleared expired cooldown for business_id={}", business.getId());
    }

    /**
     * Perform comprehensive integration checks for a business.
     */
    private IntegrationStatusResponse performIntegrationChecks(User user, Business business) {
        String accessToken = business.getAccessToken();
        String storedIgAccountId = business.getLastIgAccountIdSeen();

        // Check 1: Verify token is still valid and get pages
        log.debug("[StatusCheck] Calling /me/accounts for user_id={}", user.getId());
        PagesCheckResult pagesResult = checkFacebookPages(accessToken);

        if (pagesResult.error != null) {
            updateBusinessWithError(business, pagesResult.errorReason);
            log.info("[StatusCheck] Result: user_id={}, status=BLOCKED, reason={}", user.getId(), pagesResult.errorReason);
            pagesResult.error.setActions(buildActions());
            return pagesResult.error;
        }

        List<String> pageIds = pagesResult.pages.stream()
                .map(p -> (String) p.get("id"))
                .collect(Collectors.toList());

        log.info("[StatusCheck] Pages found: user_id={}, page_count={}, page_ids={}",
                user.getId(), pagesResult.pages.size(), pageIds);

        if (pagesResult.pages.isEmpty()) {
            updateBusinessWithError(business, "NO_PAGES_FOUND");
            log.info("[StatusCheck] Result: user_id={}, status=BLOCKED, reason=NO_PAGES_FOUND", user.getId());
            IntegrationStatusResponse response = buildNoPagesResponse();
            response.setActions(buildActions());
            return response;
        }

        // Update stored page IDs
        business.setAvailablePageIds(String.join(",", pageIds));

        // Check 2: Verify Instagram Business Account is linked
        log.debug("[StatusCheck] Checking Instagram accounts for {} pages", pagesResult.pages.size());
        InstagramCheckResult igResult = checkInstagramAccounts(business, pagesResult.pages, accessToken, storedIgAccountId);

        if (igResult.error != null) {
            updateBusinessWithError(business, igResult.errorReason);
            log.info("[StatusCheck] Result: user_id={}, status=BLOCKED, reason={}", user.getId(), igResult.errorReason);
            igResult.error.setActions(buildActions());
            return igResult.error;
        }

        // Success - update business with latest info
        business.setLastConnectionError(null);
        business.setLastStatusCheckAt(LocalDateTime.now());
        business.setIsActive(true);
        businessRepository.save(business);

        log.info("[StatusCheck] Result: user_id={}, status=CONNECTED_READY, ig_account_id={}, ig_username={}",
                user.getId(), business.getInstagramBusinessAccountId(), business.getInstagramUsername());

        IntegrationStatusResponse response = IntegrationStatusResponse.connectedReady(
                business.getInstagramUsername(),
                business.getFacebookPageId(),
                business.getName()
        );
        response.setActions(buildActions());
        return response;
    }

    /**
     * Check Facebook Pages accessibility via /me/accounts.
     */
    private PagesCheckResult checkFacebookPages(String accessToken) {
        String url = GRAPH_API_BASE + "/me/accounts?access_token=" + accessToken;

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null) {
                log.warn("[StatusCheck] Null response from /me/accounts");
                return new PagesCheckResult(buildApiErrorResponse("Empty response from Facebook API"), "API_ERROR");
            }

            // Check for API errors
            if (body.containsKey("error")) {
                return handleFacebookError(body);
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

            if (data == null) {
                log.warn("[StatusCheck] No 'data' field in /me/accounts response");
                return new PagesCheckResult(List.of());
            }

            return new PagesCheckResult(data);

        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("[StatusCheck] Token expired or invalid (401)");
            return new PagesCheckResult(buildTokenExpiredResponse(), "TOKEN_EXPIRED");
        } catch (HttpClientErrorException e) {
            log.warn("[StatusCheck] HTTP error from /me/accounts: status={}", e.getStatusCode());
            return handleHttpError(e);
        } catch (RestClientException e) {
            log.error("[StatusCheck] Network error calling /me/accounts: {}", e.getMessage());
            return new PagesCheckResult(buildApiErrorResponse("Failed to connect to Facebook: " + e.getMessage()), "API_ERROR");
        }
    }

    /**
     * Check Instagram Business Account status for each page.
     */
    private InstagramCheckResult checkInstagramAccounts(Business business, List<Map<String, Object>> pages,
                                                         String accessToken, String storedIgAccountId) {
        List<String> checkedPageIds = new ArrayList<>();
        String foundIgAccountId = null;
        String foundIgUsername = null;
        String foundPageId = null;
        String foundPageName = null;

        for (Map<String, Object> page : pages) {
            String pageId = (String) page.get("id");
            String pageName = (String) page.get("name");
            checkedPageIds.add(pageId);

            log.debug("[StatusCheck] Checking page_id={} for instagram_business_account", pageId);

            String igCheckUrl = GRAPH_API_BASE + "/" + pageId + "?fields=instagram_business_account&access_token=" + accessToken;

            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(igCheckUrl, Map.class);
                Map<String, Object> body = response.getBody();

                if (body != null && body.containsKey("instagram_business_account")) {
                    Map<String, Object> igAccount = (Map<String, Object>) body.get("instagram_business_account");
                    String igId = (String) igAccount.get("id");

                    log.info("[StatusCheck] Found instagram_business_account: page_id={}, ig_account_id={}", pageId, igId);

                    // Fetch username
                    String username = fetchInstagramUsername(igId, accessToken);

                    foundIgAccountId = igId;
                    foundIgUsername = username;
                    foundPageId = pageId;
                    foundPageName = pageName;

                    // Update business with found account
                    business.setInstagramBusinessAccountId(igId);
                    business.setInstagramUsername(username);
                    business.setFacebookPageId(pageId);
                    business.setSelectedPageId(pageId);
                    business.setLastIgAccountIdSeen(igId);
                    business.setName(pageName);

                    break; // Found one, that's enough
                } else {
                    log.debug("[StatusCheck] No instagram_business_account for page_id={}", pageId);
                }

            } catch (HttpClientErrorException e) {
                log.warn("[StatusCheck] Error checking page_id={}: status={}", pageId, e.getStatusCode());
                // Check for admin cooldown
                if (isAdminCooldownError(e)) {
                    LocalDateTime retryAt = LocalDateTime.now().plus(7, ChronoUnit.DAYS);
                    business.setConnectionRetryAt(retryAt);
                    businessRepository.save(business);
                    return new InstagramCheckResult(buildAdminCooldownResponse(business, retryAt), "ADMIN_COOLDOWN");
                }
            } catch (RestClientException e) {
                log.warn("[StatusCheck] Network error checking page_id={}: {}", pageId, e.getMessage());
            }
        }

        log.info("[StatusCheck] Instagram check complete: checked_pages={}, ig_found={}",
                checkedPageIds, foundIgAccountId != null);

        if (foundIgAccountId == null) {
            // No IG account found on any page
            // Check if we previously saw an IG account (ownership mismatch)
            if (storedIgAccountId != null) {
                log.warn("[StatusCheck] Previously saw ig_account_id={} but now not found - ownership mismatch",
                        storedIgAccountId);
                return new InstagramCheckResult(buildOwnershipMismatchResponse(business, storedIgAccountId), "OWNERSHIP_MISMATCH");
            }
            return new InstagramCheckResult(buildIgNotLinkedResponse(business, checkedPageIds), "IG_NOT_LINKED_TO_PAGE");
        }

        return new InstagramCheckResult(); // Success
    }

    /**
     * Fetch Instagram username for an account ID.
     */
    private String fetchInstagramUsername(String igAccountId, String accessToken) {
        String url = GRAPH_API_BASE + "/" + igAccountId + "?fields=username&access_token=" + accessToken;

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null) {
                return (String) body.get("username");
            }
        } catch (Exception e) {
            log.warn("[StatusCheck] Failed to fetch username for ig_account_id={}: {}", igAccountId, e.getMessage());
        }
        return null;
    }

    /**
     * Check if an error indicates admin cooldown.
     */
    private boolean isAdminCooldownError(HttpClientErrorException e) {
        String responseBody = e.getResponseBodyAsString();
        // Meta error subcode 33 = "7 day wait" / admin cooldown
        return responseBody.contains("\"error_subcode\":33") ||
               responseBody.toLowerCase().contains("7 day") ||
               responseBody.toLowerCase().contains("cooldown");
    }

    /**
     * Update business with error state.
     */
    private void updateBusinessWithError(Business business, String errorReason) {
        business.setLastConnectionError(errorReason);
        business.setLastStatusCheckAt(LocalDateTime.now());
        businessRepository.save(business);
    }

    /**
     * Handle Facebook API error responses.
     */
    private PagesCheckResult handleFacebookError(Map<String, Object> body) {
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        Integer code = (Integer) error.get("code");
        String message = (String) error.get("message");
        Integer subcode = error.get("error_subcode") != null ? (Integer) error.get("error_subcode") : null;
        String type = (String) error.get("type");
        String fbtraceId = (String) error.get("fbtrace_id");

        log.warn("[StatusCheck] Facebook API error: code={}, subcode={}, type={}, message={}", code, subcode, type, message);

        // Build the API error object for the response
        IntegrationStatusResponse.ApiError apiError = new IntegrationStatusResponse.ApiError(
                code, subcode, type, message, fbtraceId
        );

        // Error code 190: Invalid/expired token
        if (code != null && code == 190) {
            IntegrationStatusResponse response = buildTokenExpiredResponse();
            response.setApiError(apiError);
            return new PagesCheckResult(response, "TOKEN_EXPIRED");
        }

        // Error code 10 or 200: Permission error
        if (code != null && (code == 10 || code == 200)) {
            IntegrationStatusResponse response = buildMissingPermissionsResponse(message);
            response.setApiError(apiError);
            return new PagesCheckResult(response, "MISSING_PERMISSIONS");
        }

        IntegrationStatusResponse response = buildApiErrorResponse(message);
        response.setApiError(apiError);
        return new PagesCheckResult(response, "API_ERROR");
    }

    /**
     * Handle HTTP errors from Graph API.
     */
    private PagesCheckResult handleHttpError(HttpClientErrorException e) {
        // Try to parse error body for detailed API error
        IntegrationStatusResponse.ApiError apiError = parseApiErrorFromException(e);

        if (e.getStatusCode().value() == 401) {
            IntegrationStatusResponse response = buildTokenExpiredResponse();
            response.setApiError(apiError);
            return new PagesCheckResult(response, "TOKEN_EXPIRED");
        }
        IntegrationStatusResponse response = buildApiErrorResponse("Facebook API returned " + e.getStatusCode());
        response.setApiError(apiError);
        return new PagesCheckResult(response, "API_ERROR");
    }

    /**
     * Parse API error details from HTTP exception body.
     */
    private IntegrationStatusResponse.ApiError parseApiErrorFromException(HttpClientErrorException e) {
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && responseBody.contains("\"error\"")) {
                // Simple JSON parsing for error object
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> body = mapper.readValue(responseBody, Map.class);
                if (body.containsKey("error")) {
                    Map<String, Object> error = (Map<String, Object>) body.get("error");
                    Integer code = error.get("code") != null ? ((Number) error.get("code")).intValue() : null;
                    Integer subcode = error.get("error_subcode") != null ? ((Number) error.get("error_subcode")).intValue() : null;
                    String type = (String) error.get("type");
                    String message = (String) error.get("message");
                    String fbtraceId = (String) error.get("fbtrace_id");
                    return new IntegrationStatusResponse.ApiError(code, subcode, type, message, fbtraceId);
                }
            }
        } catch (Exception ex) {
            log.debug("[StatusCheck] Failed to parse error body: {}", ex.getMessage());
        }
        // Fallback: create minimal error from exception
        return new IntegrationStatusResponse.ApiError(
                e.getStatusCode().value(), null, "HttpError",
                e.getStatusText() != null ? e.getStatusText() : "HTTP " + e.getStatusCode().value(),
                null
        );
    }

    // Response builders - User-friendly messages only, no Meta jargon

    private IntegrationStatusResponse buildNoPagesResponse() {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.NO_PAGES_FOUND,
                "Your account doesn't have the required setup. Please create a business page first."
        );
        response.setNextActions(List.of(
                new NextAction("LINK", "Create a Page", "https://www.facebook.com/pages/create"),
                new NextAction("HELP", "Learn how to set up", "https://help.instagram.com/399237934150902")
        ));
        return response;
    }

    private IntegrationStatusResponse buildIgNotLinkedResponse(Business business, List<String> checkedPageIds) {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.IG_NOT_LINKED_TO_PAGE,
                "Instagram isn't fully set up yet. Please connect your Instagram account to your page."
        );
        response.setDetails(Map.of(
                "businessName", business.getName() != null ? business.getName() : "",
                "pagesChecked", checkedPageIds.size()
        ));
        response.setNextActions(List.of(
                new NextAction("LINK", "Connect Instagram", "https://www.facebook.com/settings/?tab=linked_instagram"),
                new NextAction("HELP", "Learn how to connect", "https://help.instagram.com/399237934150902")
        ));
        return response;
    }

    private IntegrationStatusResponse buildOwnershipMismatchResponse(Business business, String previousIgAccountId) {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.OWNERSHIP_MISMATCH,
                "Your Instagram account is no longer accessible. This can happen if account permissions changed. Please reconnect or contact your team admin."
        );
        response.setDetails(Map.of(
                "instagramUsername", business.getInstagramUsername() != null ? business.getInstagramUsername() : ""
        ));
        response.setNextActions(List.of(
                new NextAction("RECONNECT", "Reconnect account", null),
                new NextAction("HELP", "Contact your admin", null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildAdminCooldownResponse(Business business, LocalDateTime retryAt) {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.ADMIN_COOLDOWN,
                "There's a 7-day waiting period for new account setups. Please try again after the wait period."
        );
        response.setRetryAt(retryAt.atZone(ZoneId.systemDefault()).toInstant());
        response.setDetails(Map.of(
                "instagramUsername", business.getInstagramUsername() != null ? business.getInstagramUsername() : ""
        ));
        response.setNextActions(List.of(
                new NextAction("WAIT", "Try again on " + retryAt.toLocalDate().toString(), null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildTokenExpiredResponse() {
        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.TOKEN_EXPIRED,
                "Your connection has expired. Please reconnect your account."
        );
        response.setNextActions(List.of(
                new NextAction("RECONNECT", "Reconnect", null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildMissingPermissionsResponse(String internalMessage) {
        // Log the actual API message server-side only
        log.debug("[StatusCheck] Missing permissions API message: {}", internalMessage);

        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.MISSING_PERMISSIONS,
                "Some permissions weren't granted. Please reconnect and approve all requested permissions."
        );
        // Don't expose API message to frontend
        response.setNextActions(List.of(
                new NextAction("RECONNECT", "Reconnect with permissions", null)
        ));
        return response;
    }

    private IntegrationStatusResponse buildApiErrorResponse(String internalMessage) {
        // Log the actual error message server-side only
        log.debug("[StatusCheck] API error message: {}", internalMessage);

        IntegrationStatusResponse response = IntegrationStatusResponse.blocked(
                Reason.API_ERROR,
                "Something went wrong while checking your account. Please try again."
        );
        // Don't expose raw error to frontend
        response.setNextActions(List.of(
                new NextAction("RETRY", "Try again", null),
                new NextAction("RECONNECT", "Reconnect", null)
        ));
        return response;
    }

    // Helper classes

    private static class PagesCheckResult {
        List<Map<String, Object>> pages;
        IntegrationStatusResponse error;
        String errorReason;

        PagesCheckResult(List<Map<String, Object>> pages) {
            this.pages = pages;
        }

        PagesCheckResult(IntegrationStatusResponse error, String errorReason) {
            this.error = error;
            this.errorReason = errorReason;
            this.pages = List.of();
        }
    }

    private static class InstagramCheckResult {
        IntegrationStatusResponse error;
        String errorReason;

        InstagramCheckResult() {
            // Success case
        }

        InstagramCheckResult(IntegrationStatusResponse error, String errorReason) {
            this.error = error;
            this.errorReason = errorReason;
        }
    }
}
