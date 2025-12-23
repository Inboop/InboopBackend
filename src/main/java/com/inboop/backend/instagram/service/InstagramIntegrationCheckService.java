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

        log.info("[StatusCheck] /me/accounts returned {} pages for user_id={}", pagesResult.pages.size(), user.getId());

        // If /me/accounts is empty, try /me/businesses -> owned_pages (Business Portfolio pages)
        if (pagesResult.pages.isEmpty()) {
            log.info("[StatusCheck] No direct pages found, checking /me/businesses for Business Portfolio pages");
            PagesCheckResult businessPagesResult = checkBusinessOwnedPages(accessToken);

            if (businessPagesResult.error != null) {
                // Log but don't fail - we'll return NO_PAGES_FOUND with combined diagnostics
                log.warn("[StatusCheck] Error checking business-owned pages: {}", businessPagesResult.errorReason);
            } else if (!businessPagesResult.pages.isEmpty()) {
                log.info("[StatusCheck] Found {} Business Portfolio-owned pages", businessPagesResult.pages.size());
                pagesResult = businessPagesResult;
            }
        }

        List<String> pageIds = pagesResult.pages.stream()
                .map(p -> (String) p.get("id"))
                .collect(Collectors.toList());

        log.info("[StatusCheck] Total pages found: user_id={}, page_count={}, page_ids={}",
                user.getId(), pagesResult.pages.size(), pageIds);

        // Only return NO_PAGES_FOUND if BOTH user-owned AND business-owned pages returned empty
        if (pagesResult.pages.isEmpty()) {
            updateBusinessWithError(business, "NO_PAGES_FOUND");
            log.info("[StatusCheck] Result: user_id={}, status=BLOCKED, reason=NO_PAGES_FOUND (checked both /me/accounts and /me/businesses)", user.getId());

            IntegrationStatusResponse response;
            // If we have token debug info, it means permissions are likely missing
            if (pagesResult.debugInfo != null) {
                boolean hasPagesShowList = Boolean.TRUE.equals(pagesResult.debugInfo.get("has_pages_show_list"));
                boolean hasPagesReadEngagement = Boolean.TRUE.equals(pagesResult.debugInfo.get("has_pages_read_engagement"));
                boolean hasBusinessManagement = Boolean.TRUE.equals(pagesResult.debugInfo.get("has_business_management"));

                if (!hasPagesShowList && !hasPagesReadEngagement && !hasBusinessManagement) {
                    // Missing required permissions - tell user to reconnect
                    response = IntegrationStatusResponse.blocked(
                            Reason.MISSING_PERMISSIONS,
                            "Missing required permissions. Please reconnect and approve all requested permissions."
                    );
                    response.setNextActions(List.of(
                            new NextAction("RECONNECT", "Reconnect Account", null)
                    ));
                    // Include debug info in details
                    response.setDetails(Map.of(
                            "grantedPermissions", pagesResult.debugInfo.get("permissions"),
                            "missingPermissions", List.of("pages_show_list", "pages_read_engagement", "business_management"),
                            "appId", pagesResult.debugInfo.get("app_id")
                    ));
                    // Create synthetic API error for UI display
                    response.setApiError(new IntegrationStatusResponse.ApiError(
                            10, null, "OAuthException",
                            "Token does not have required permissions: pages_show_list, pages_read_engagement, business_management. Granted: " + pagesResult.tokenPermissions,
                            null
                    ));
                } else {
                    // Has permissions but no pages - user genuinely has no pages
                    response = buildNoPagesResponse();
                    response.setDetails(Map.of(
                            "grantedPermissions", pagesResult.debugInfo.get("permissions"),
                            "appId", pagesResult.debugInfo.get("app_id"),
                            "note", "Token has page permissions but both /me/accounts and /me/businesses returned no pages with Instagram accounts."
                    ));
                }
            } else {
                response = buildNoPagesResponse();
            }

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
     * Returns pages with their page access tokens for subsequent API calls.
     *
     * Also checks token permissions if no pages are found.
     */
    private PagesCheckResult checkFacebookPages(String accessToken) {
        String url = GRAPH_API_BASE + "/me/accounts?fields=id,name,access_token&access_token=" + accessToken;

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            // Log raw response for debugging (without tokens)
            if (body != null) {
                log.info("[StatusCheck] /me/accounts response: data_count={}, has_error={}, keys={}",
                        body.containsKey("data") ? ((List<?>) body.get("data")).size() : 0,
                        body.containsKey("error"),
                        body.keySet());
            }

            if (body == null) {
                log.warn("[StatusCheck] Null response from /me/accounts");
                IntegrationStatusResponse errorResponse = buildApiErrorResponse("Empty response from Facebook API");
                errorResponse.setApiError(new IntegrationStatusResponse.ApiError(
                        null, null, "EmptyResponse", "Facebook API returned an empty response", null
                ));
                return new PagesCheckResult(errorResponse, "API_ERROR");
            }

            // Check for API errors
            if (body.containsKey("error")) {
                return handleFacebookError(body);
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

            if (data == null) {
                log.warn("[StatusCheck] No 'data' field in /me/accounts response. Full response keys: {}", body.keySet());
                return new PagesCheckResult(List.of());
            }

            // If no pages found, check token permissions to provide better diagnostics
            if (data.isEmpty()) {
                log.warn("[StatusCheck] /me/accounts returned empty data array - checking token permissions");
                TokenPermissionsResult permResult = checkTokenPermissions(accessToken);
                log.info("[StatusCheck] Token permissions: {}", permResult.permissions);
                log.info("[StatusCheck] Token app_id: {}, user_id: {}", permResult.appId, permResult.userId);

                // Return empty list but with diagnostic info in result
                PagesCheckResult result = new PagesCheckResult(List.of());
                result.tokenPermissions = permResult.permissions;
                result.debugInfo = Map.of(
                        "permissions", permResult.permissions != null ? permResult.permissions : List.of(),
                        "app_id", permResult.appId != null ? permResult.appId : "",
                        "user_id", permResult.userId != null ? permResult.userId : "",
                        "has_pages_show_list", permResult.permissions != null && permResult.permissions.contains("pages_show_list"),
                        "has_pages_read_engagement", permResult.permissions != null && permResult.permissions.contains("pages_read_engagement"),
                        "has_business_management", permResult.permissions != null && permResult.permissions.contains("business_management")
                );
                return result;
            }

            // Log each page found (without exposing tokens)
            for (Map<String, Object> page : data) {
                log.info("[StatusCheck] Found page: id={}, name={}, has_access_token={}",
                        page.get("id"), page.get("name"), page.containsKey("access_token"));
            }

            return new PagesCheckResult(data);

        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("[StatusCheck] Token expired or invalid (401)");
            IntegrationStatusResponse.ApiError apiError = parseApiErrorFromException(e);
            IntegrationStatusResponse response = buildTokenExpiredResponse();
            response.setApiError(apiError);
            return new PagesCheckResult(response, "TOKEN_EXPIRED");
        } catch (HttpClientErrorException e) {
            log.warn("[StatusCheck] HTTP error from /me/accounts: status={}", e.getStatusCode());
            return handleHttpError(e);
        } catch (RestClientException e) {
            log.error("[StatusCheck] Network error calling /me/accounts: {}", e.getMessage());
            IntegrationStatusResponse response = buildApiErrorResponse("Failed to connect to Facebook: " + e.getMessage());
            response.setApiError(new IntegrationStatusResponse.ApiError(
                    null, null, "NetworkError", e.getMessage(), null
            ));
            return new PagesCheckResult(response, "API_ERROR");
        }
    }

    /**
     * Check for Business Portfolio-owned pages via /me/businesses -> /{business-id}/owned_pages.
     * This is the fallback when /me/accounts returns empty (user is not a direct Page admin).
     *
     * Uses user access token (not page token) for business queries.
     * For each owned page found, queries for Instagram accounts.
     */
    private PagesCheckResult checkBusinessOwnedPages(String accessToken) {
        List<Map<String, Object>> allPages = new ArrayList<>();

        // Step 1: Get all businesses the user has access to
        String businessesUrl = GRAPH_API_BASE + "/me/businesses?fields=id,name&access_token=" + accessToken;

        try {
            ResponseEntity<Map> businessResponse = restTemplate.getForEntity(businessesUrl, Map.class);
            Map<String, Object> businessBody = businessResponse.getBody();

            if (businessBody == null || !businessBody.containsKey("data")) {
                log.info("[StatusCheck] /me/businesses returned empty or no data");
                return new PagesCheckResult(List.of());
            }

            List<Map<String, Object>> businesses = (List<Map<String, Object>>) businessBody.get("data");
            log.info("[StatusCheck] Found {} businesses via /me/businesses", businesses.size());

            if (businesses.isEmpty()) {
                return new PagesCheckResult(List.of());
            }

            // Step 2: For each business, get owned_pages
            for (Map<String, Object> business : businesses) {
                String businessId = (String) business.get("id");
                String businessName = (String) business.get("name");
                log.info("[StatusCheck] Checking business_id={} ({}) for owned_pages", businessId, businessName);

                String ownedPagesUrl = GRAPH_API_BASE + "/" + businessId +
                        "/owned_pages?fields=id,name,access_token&access_token=" + accessToken;

                try {
                    ResponseEntity<Map> pagesResponse = restTemplate.getForEntity(ownedPagesUrl, Map.class);
                    Map<String, Object> pagesBody = pagesResponse.getBody();

                    if (pagesBody != null && pagesBody.containsKey("data")) {
                        List<Map<String, Object>> ownedPages = (List<Map<String, Object>>) pagesBody.get("data");
                        log.info("[StatusCheck] Business {} has {} owned pages", businessId, ownedPages.size());

                        for (Map<String, Object> page : ownedPages) {
                            String pageId = (String) page.get("id");
                            String pageName = (String) page.get("name");
                            boolean hasPageToken = page.containsKey("access_token");
                            log.info("[StatusCheck] Found owned page: id={}, name={}, has_access_token={}",
                                    pageId, pageName, hasPageToken);
                            allPages.add(page);
                        }
                    }
                } catch (HttpClientErrorException e) {
                    log.warn("[StatusCheck] Error fetching owned_pages for business_id={}: status={}, body={}",
                            businessId, e.getStatusCode(), e.getResponseBodyAsString());
                    // Continue to next business
                } catch (RestClientException e) {
                    log.warn("[StatusCheck] Network error fetching owned_pages for business_id={}: {}",
                            businessId, e.getMessage());
                }
            }

            log.info("[StatusCheck] Total pages found via /me/businesses: {}", allPages.size());
            return new PagesCheckResult(allPages);

        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("[StatusCheck] Token expired or invalid during /me/businesses call (401)");
            IntegrationStatusResponse.ApiError apiError = parseApiErrorFromException(e);
            IntegrationStatusResponse response = buildTokenExpiredResponse();
            response.setApiError(apiError);
            return new PagesCheckResult(response, "TOKEN_EXPIRED");
        } catch (HttpClientErrorException e) {
            log.warn("[StatusCheck] HTTP error from /me/businesses: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            // Check if it's a permission error (business_management scope required)
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 403) {
                String responseBody = e.getResponseBodyAsString();
                if (responseBody.contains("permission") || responseBody.contains("business_management")) {
                    log.info("[StatusCheck] /me/businesses requires business_management permission - not granted");
                    // Return empty list (not an error) - will fall through to NO_PAGES_FOUND with diagnostics
                    return new PagesCheckResult(List.of());
                }
            }
            IntegrationStatusResponse.ApiError apiError = parseApiErrorFromException(e);
            IntegrationStatusResponse response = buildApiErrorResponse("Error checking business pages: " + e.getStatusCode());
            response.setApiError(apiError);
            return new PagesCheckResult(response, "API_ERROR");
        } catch (RestClientException e) {
            log.error("[StatusCheck] Network error calling /me/businesses: {}", e.getMessage());
            // Return empty list (not an error) - will try to proceed with whatever was found
            return new PagesCheckResult(List.of());
        }
    }

    /**
     * Check Instagram Business Account status for each page.
     * Uses PAGE access token (not user token) for querying instagram_business_account.
     *
     * @param business The business entity to update
     * @param pages List of pages from /me/accounts (includes page access tokens)
     * @param userAccessToken The user access token (fallback only)
     * @param storedIgAccountId Previously seen IG account ID (for ownership mismatch detection)
     */
    private InstagramCheckResult checkInstagramAccounts(Business business, List<Map<String, Object>> pages,
                                                         String userAccessToken, String storedIgAccountId) {
        List<String> checkedPageIds = new ArrayList<>();
        String foundIgAccountId = null;
        String foundIgUsername = null;
        String foundPageId = null;
        String foundPageName = null;

        for (Map<String, Object> page : pages) {
            String pageId = (String) page.get("id");
            String pageName = (String) page.get("name");
            // CRITICAL: Use page access token, not user access token
            String pageAccessToken = (String) page.get("access_token");
            checkedPageIds.add(pageId);

            // Determine which token to use
            String tokenToUse = pageAccessToken != null ? pageAccessToken : userAccessToken;
            String tokenType = pageAccessToken != null ? "page_token" : "user_token";

            log.info("[StatusCheck] Checking page_id={} ({}) for instagram_business_account using {}",
                    pageId, pageName, tokenType);

            // Query for both instagram_business_account AND connected_instagram_account
            String igCheckUrl = GRAPH_API_BASE + "/" + pageId +
                    "?fields=instagram_business_account,connected_instagram_account&access_token=" + tokenToUse;

            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(igCheckUrl, Map.class);
                Map<String, Object> body = response.getBody();

                // Log the raw response structure (without token values)
                if (body != null) {
                    log.info("[StatusCheck] Page {} response: has_instagram_business_account={}, has_connected_instagram_account={}, keys={}",
                            pageId,
                            body.containsKey("instagram_business_account"),
                            body.containsKey("connected_instagram_account"),
                            body.keySet());
                }

                // First try instagram_business_account (primary)
                if (body != null && body.containsKey("instagram_business_account")) {
                    Map<String, Object> igAccount = (Map<String, Object>) body.get("instagram_business_account");
                    String igId = (String) igAccount.get("id");

                    log.info("[StatusCheck] Found instagram_business_account: page_id={}, ig_account_id={}", pageId, igId);

                    // Fetch username using page token
                    String username = fetchInstagramUsername(igId, tokenToUse);

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
                }

                // Fallback: try connected_instagram_account
                if (body != null && body.containsKey("connected_instagram_account")) {
                    Map<String, Object> igAccount = (Map<String, Object>) body.get("connected_instagram_account");
                    String igId = (String) igAccount.get("id");

                    log.info("[StatusCheck] Found connected_instagram_account (fallback): page_id={}, ig_account_id={}", pageId, igId);

                    String username = fetchInstagramUsername(igId, tokenToUse);

                    foundIgAccountId = igId;
                    foundIgUsername = username;
                    foundPageId = pageId;
                    foundPageName = pageName;

                    business.setInstagramBusinessAccountId(igId);
                    business.setInstagramUsername(username);
                    business.setFacebookPageId(pageId);
                    business.setSelectedPageId(pageId);
                    business.setLastIgAccountIdSeen(igId);
                    business.setName(pageName);

                    break;
                }

                log.info("[StatusCheck] No Instagram account linked to page_id={} ({})", pageId, pageName);

            } catch (HttpClientErrorException e) {
                log.warn("[StatusCheck] Error checking page_id={}: status={}, body={}",
                        pageId, e.getStatusCode(), e.getResponseBodyAsString());
                // Check for admin cooldown
                if (isAdminCooldownError(e)) {
                    LocalDateTime retryAt = LocalDateTime.now().plus(7, ChronoUnit.DAYS);
                    business.setConnectionRetryAt(retryAt);
                    businessRepository.save(business);
                    IntegrationStatusResponse cooldownResponse = buildAdminCooldownResponse(business, retryAt);
                    cooldownResponse.setApiError(parseApiErrorFromException(e));
                    return new InstagramCheckResult(cooldownResponse, "ADMIN_COOLDOWN");
                }
                // Log detailed error but continue checking other pages
                IntegrationStatusResponse.ApiError apiError = parseApiErrorFromException(e);
                log.warn("[StatusCheck] API error for page {}: code={}, message={}",
                        pageId, apiError.getCode(), apiError.getMessage());
            } catch (RestClientException e) {
                log.warn("[StatusCheck] Network error checking page_id={}: {}", pageId, e.getMessage());
            }
        }

        log.info("[StatusCheck] Instagram check complete: checked_pages={}, ig_found={}, ig_id={}, ig_username={}",
                checkedPageIds, foundIgAccountId != null, foundIgAccountId, foundIgUsername);

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

    /**
     * Check token permissions via /me/permissions endpoint.
     * Used for diagnostics when /me/accounts returns empty.
     */
    private TokenPermissionsResult checkTokenPermissions(String accessToken) {
        TokenPermissionsResult result = new TokenPermissionsResult();

        // Check permissions
        try {
            String permUrl = GRAPH_API_BASE + "/me/permissions?access_token=" + accessToken;
            ResponseEntity<Map> permResponse = restTemplate.getForEntity(permUrl, Map.class);
            Map<String, Object> permBody = permResponse.getBody();

            if (permBody != null && permBody.containsKey("data")) {
                List<Map<String, Object>> permData = (List<Map<String, Object>>) permBody.get("data");
                result.permissions = permData.stream()
                        .filter(p -> "granted".equals(p.get("status")))
                        .map(p -> (String) p.get("permission"))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("[StatusCheck] Failed to check token permissions: {}", e.getMessage());
        }

        // Check token debug info
        try {
            String debugUrl = GRAPH_API_BASE + "/debug_token?input_token=" + accessToken + "&access_token=" + accessToken;
            ResponseEntity<Map> debugResponse = restTemplate.getForEntity(debugUrl, Map.class);
            Map<String, Object> debugBody = debugResponse.getBody();

            if (debugBody != null && debugBody.containsKey("data")) {
                Map<String, Object> data = (Map<String, Object>) debugBody.get("data");
                result.appId = data.get("app_id") != null ? data.get("app_id").toString() : null;
                result.userId = data.get("user_id") != null ? data.get("user_id").toString() : null;
                result.isValid = Boolean.TRUE.equals(data.get("is_valid"));

                // Log scopes from debug_token as well
                if (data.containsKey("scopes")) {
                    log.info("[StatusCheck] Token scopes from debug_token: {}", data.get("scopes"));
                }
            }
        } catch (Exception e) {
            log.warn("[StatusCheck] Failed to debug token: {}", e.getMessage());
        }

        return result;
    }

    // Helper classes

    private static class TokenPermissionsResult {
        List<String> permissions;
        String appId;
        String userId;
        boolean isValid;
    }

    private static class PagesCheckResult {
        List<Map<String, Object>> pages;
        IntegrationStatusResponse error;
        String errorReason;
        List<String> tokenPermissions;
        Map<String, Object> debugInfo;

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
