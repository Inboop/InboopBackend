package com.inboop.backend.instagram.service;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.business.entity.Business;
import com.inboop.backend.business.repository.BusinessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for fetching Facebook Pages and Instagram Business accounts.
 *
 * Flow:
 * 1. Use Facebook user access token to fetch Pages via /me/accounts
 * 2. For each Page, fetch instagram_business_account field
 * 3. Persist the mapping (User -> Page -> Instagram Business Account)
 */
@Service
public class InstagramBusinessService {

    private static final Logger log = LoggerFactory.getLogger(InstagramBusinessService.class);
    private static final String GRAPH_API_BASE = "https://graph.facebook.com/v21.0";

    private final BusinessRepository businessRepository;
    private final RestTemplate restTemplate;

    @Value("${instagram.api.version:v21.0}")
    private String apiVersion;

    public InstagramBusinessService(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Fetch Facebook Pages and linked Instagram Business accounts for the user.
     * Stores connection context in database for later status checks.
     *
     * CRITICAL: This method now:
     * 1. Clears ALL existing businesses for this user first (clean reconnect)
     * 2. Requests page access tokens explicitly
     * 3. Uses PAGE tokens (not user token) for IG queries
     * 4. Checks BOTH instagram_business_account AND connected_instagram_account
     * 5. Iterates ALL pages, not just the first one
     *
     * @param user           The Inboop user who authorized the connection
     * @param facebookUserId The Facebook user ID from OAuth
     * @param accessToken    The Facebook user access token
     * @param tokenExpiresAt When the token expires (nullable)
     * @return List of connected Business entities
     */
    @Transactional
    public List<Business> connectInstagramAccounts(User user, String facebookUserId,
                                                    String accessToken, Instant tokenExpiresAt) {
        String tokenHash = accessToken.length() > 6 ? accessToken.substring(accessToken.length() - 6) : accessToken;
        log.info("[OAuth] ========== STARTING INSTAGRAM CONNECTION ==========");
        log.info("[OAuth] user_id={}, facebook_user_id={}, token_hash=...{}", user.getId(), facebookUserId, tokenHash);

        // CRITICAL FIX: Clear ALL existing businesses for this user first
        // This ensures we don't reuse stale tokens or old page data
        List<Business> existingBusinesses = businessRepository.findByOwnerId(user.getId());
        if (!existingBusinesses.isEmpty()) {
            log.info("[OAuth] CLEARING {} existing businesses for user_id={} to ensure fresh connection",
                    existingBusinesses.size(), user.getId());
            for (Business b : existingBusinesses) {
                log.info("[OAuth] Deleting old business: id={}, ig_account={}, page_id={}",
                        b.getId(), b.getInstagramBusinessAccountId(), b.getFacebookPageId());
                businessRepository.delete(b);
            }
            businessRepository.flush(); // Ensure deletes are committed
        }

        List<Business> connectedBusinesses = new ArrayList<>();
        List<String> allPageIds = new ArrayList<>();

        try {
            // Step 1: Fetch Facebook Pages the user manages (WITH page access tokens)
            List<FacebookPage> pages = fetchFacebookPages(accessToken);

            // Collect all page IDs for connection context
            for (FacebookPage page : pages) {
                allPageIds.add(page.id);
            }
            String availablePageIds = String.join(",", allPageIds);

            log.info("[OAuth] PAGES DISCOVERY: Found {} Facebook Pages for user_id={}", pages.size(), user.getId());
            for (FacebookPage page : pages) {
                boolean hasPageToken = page.accessToken != null && !page.accessToken.isEmpty();
                log.info("[OAuth]   -> Page: id={}, name='{}', has_page_token={}", page.id, page.name, hasPageToken);
            }

            if (pages.isEmpty()) {
                log.warn("[OAuth] NO PAGES FOUND for user_id={}. Check pages_show_list permission.", user.getId());
                storeConnectionErrorContext(user, facebookUserId, accessToken, tokenExpiresAt,
                        null, "NO_PAGES_FOUND");
                return connectedBusinesses;
            }

            // Step 2: For EACH Page, check for linked Instagram Business Account
            // CRITICAL: Use PAGE token, not user token. Check BOTH IG fields.
            int pagesWithIg = 0;
            int pagesWithoutIg = 0;

            for (FacebookPage page : pages) {
                try {
                    log.info("[OAuth] CHECKING PAGE FOR IG: page_id={}, page_name='{}'", page.id, page.name);

                    // Use PAGE access token if available, otherwise fall back to user token
                    String tokenToUse = (page.accessToken != null && !page.accessToken.isEmpty())
                            ? page.accessToken
                            : accessToken;
                    String tokenType = (page.accessToken != null && !page.accessToken.isEmpty())
                            ? "PAGE_TOKEN"
                            : "USER_TOKEN";
                    log.info("[OAuth] Using {} for IG query on page_id={}", tokenType, page.id);

                    InstagramAccount igAccount = fetchInstagramBusinessAccount(page.id, tokenToUse);

                    if (igAccount != null) {
                        pagesWithIg++;
                        log.info("[OAuth] IG ACCOUNT FOUND: ig_id={}, ig_username={}, page_id={}, page_name='{}'",
                                igAccount.id, igAccount.username, page.id, page.name);

                        // Step 3: Create NEW business (we deleted old ones above)
                        Business business = new Business();
                        business.setOwner(user);
                        business.setName(page.name);
                        business.setFacebookUserId(facebookUserId);
                        business.setFacebookPageId(page.id);
                        business.setInstagramBusinessAccountId(igAccount.id);
                        if (igAccount.username != null) {
                            business.setInstagramUsername(igAccount.username);
                        }
                        business.setAccessToken(accessToken); // Store user token for later API calls
                        if (tokenExpiresAt != null) {
                            business.setTokenExpiresAt(LocalDateTime.ofInstant(tokenExpiresAt, ZoneId.systemDefault()));
                        }
                        business.setIsActive(true);

                        // Store connection context
                        business.setAvailablePageIds(availablePageIds);
                        business.setSelectedPageId(page.id);
                        business.setLastIgAccountIdSeen(igAccount.id);
                        business.setLastConnectionError(null);
                        business.setConnectionRetryAt(null);
                        business.setLastStatusCheckAt(LocalDateTime.now());

                        businessRepository.save(business);
                        connectedBusinesses.add(business);

                        log.info("[OAuth] SAVED BUSINESS: id={}, ig_account_id={}, ig_username={}, page_id={}",
                                business.getId(), igAccount.id, igAccount.username, page.id);
                    } else {
                        pagesWithoutIg++;
                        log.info("[OAuth] NO IG ACCOUNT: page_id={}, page_name='{}' has no linked Instagram",
                                page.id, page.name);
                    }
                } catch (Exception e) {
                    log.error("[OAuth] ERROR checking page_id={}: {}", page.id, e.getMessage(), e);
                }
            }

            log.info("[OAuth] ========== CONNECTION COMPLETE ==========");
            log.info("[OAuth] user_id={}: pages_checked={}, pages_with_ig={}, pages_without_ig={}, total_connected={}",
                    user.getId(), pages.size(), pagesWithIg, pagesWithoutIg, connectedBusinesses.size());

            // If no IG accounts found, store error context
            if (connectedBusinesses.isEmpty() && !pages.isEmpty()) {
                log.warn("[OAuth] NO INSTAGRAM ACCOUNTS FOUND for user_id={} despite having {} pages",
                        user.getId(), pages.size());
                storeConnectionErrorContext(user, facebookUserId, accessToken, tokenExpiresAt,
                        availablePageIds, "IG_NOT_LINKED_TO_PAGE");
            }

        } catch (Exception e) {
            log.error("[OAuth] FATAL ERROR for user_id={}: {}", user.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to connect Instagram accounts: " + e.getMessage(), e);
        }

        return connectedBusinesses;
    }

    /**
     * Store connection error context when OAuth succeeds but no IG accounts found.
     * This allows the status endpoint to provide meaningful feedback.
     */
    private void storeConnectionErrorContext(User user, String facebookUserId, String accessToken,
                                             Instant tokenExpiresAt, String availablePageIds, String errorReason) {
        // Find or create a placeholder business for this user
        List<Business> existingBusinesses = businessRepository.findByOwnerId(user.getId());
        Business business;

        if (existingBusinesses.isEmpty()) {
            business = new Business();
            business.setOwner(user);
            business.setName("Pending Connection");
        } else {
            business = existingBusinesses.get(0);
        }

        business.setFacebookUserId(facebookUserId);
        business.setAccessToken(accessToken);
        if (tokenExpiresAt != null) {
            business.setTokenExpiresAt(LocalDateTime.ofInstant(tokenExpiresAt, ZoneId.systemDefault()));
        }
        business.setIsActive(false); // Not active since no IG account linked
        business.setAvailablePageIds(availablePageIds);
        business.setLastConnectionError(errorReason);
        business.setLastStatusCheckAt(LocalDateTime.now());

        businessRepository.save(business);
        log.info("[OAuth] Stored connection error context: user_id={}, error={}", user.getId(), errorReason);
    }

    /**
     * Fetch all Facebook Pages the user manages.
     * GET https://graph.facebook.com/v21.0/me/accounts?fields=id,name,access_token
     *
     * CRITICAL: Must request access_token field explicitly to get page tokens!
     */
    private List<FacebookPage> fetchFacebookPages(String accessToken) {
        // CRITICAL FIX: Explicitly request id, name, and access_token fields
        String url = GRAPH_API_BASE + "/me/accounts?fields=id,name,access_token&access_token=" + accessToken;
        log.info("[OAuth] FETCHING PAGES: GET /me/accounts?fields=id,name,access_token");

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();

            // INSTRUMENTATION: Log raw response
            log.info("[OAuth] RAW /me/accounts RESPONSE: {}", body);

            if (body == null) {
                log.warn("[OAuth] NULL response from /me/accounts");
                return List.of();
            }

            // Check for error in response
            if (body.containsKey("error")) {
                Map<String, Object> error = (Map<String, Object>) body.get("error");
                log.error("[OAuth] /me/accounts ERROR: code={}, message={}", error.get("code"), error.get("message"));
                throw new RuntimeException("Facebook API error: " + error.get("message"));
            }

            if (!body.containsKey("data")) {
                log.warn("[OAuth] /me/accounts NO 'data' field. Full response: {}", body);
                return List.of();
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

            if (data == null || data.isEmpty()) {
                log.warn("[OAuth] /me/accounts returned EMPTY data array. User has no Pages or lacks pages_show_list permission.");
                return List.of();
            }

            List<FacebookPage> pages = new ArrayList<>();

            log.info("[OAuth] /me/accounts returned {} pages:", data.size());
            for (Map<String, Object> pageData : data) {
                FacebookPage page = new FacebookPage();
                page.id = (String) pageData.get("id");
                page.name = (String) pageData.get("name");
                page.accessToken = (String) pageData.get("access_token"); // Page access token

                boolean hasToken = page.accessToken != null && !page.accessToken.isEmpty();
                String tokenPreview = hasToken ? ("..." + page.accessToken.substring(Math.max(0, page.accessToken.length() - 6))) : "NONE";
                log.info("[OAuth]   Page #{}: id={}, name='{}', page_token={}", pages.size() + 1, page.id, page.name, tokenPreview);

                pages.add(page);
            }

            return pages;
        } catch (RestClientException e) {
            log.error("[OAuth] FAILED to fetch /me/accounts: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch Facebook Pages: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch the Instagram Business Account linked to a Facebook Page.
     *
     * CRITICAL FIX: Check BOTH fields:
     * - instagram_business_account (standard Business accounts)
     * - connected_instagram_account (Creator accounts or alternate linking)
     *
     * Step 1: GET /{page-id}?fields=instagram_business_account,connected_instagram_account
     * Step 2: GET /{ig-account-id}?fields=id,username,name,profile_picture_url to get details
     *
     * @return InstagramAccount with id and username, or null if not linked
     */
    private InstagramAccount fetchInstagramBusinessAccount(String pageId, String accessToken) {
        // CRITICAL FIX: Request BOTH instagram_business_account AND connected_instagram_account
        String pageUrl = GRAPH_API_BASE + "/" + pageId +
                "?fields=instagram_business_account,connected_instagram_account&access_token=" + accessToken;

        log.info("[OAuth] FETCHING IG FOR PAGE: GET /{}?fields=instagram_business_account,connected_instagram_account", pageId);

        try {
            ResponseEntity<Map> pageResponse = restTemplate.getForEntity(pageUrl, Map.class);
            Map<String, Object> pageBody = pageResponse.getBody();

            // INSTRUMENTATION: Log raw response
            log.info("[OAuth] RAW /{} IG RESPONSE: {}", pageId, pageBody);

            if (pageBody == null) {
                log.info("[OAuth] NULL response for page_id={}", pageId);
                return null;
            }

            String igAccountId = null;
            String sourceField = null;

            // Check instagram_business_account first (standard field)
            Map<String, Object> businessAccountRef = (Map<String, Object>) pageBody.get("instagram_business_account");
            if (businessAccountRef != null && businessAccountRef.get("id") != null) {
                igAccountId = (String) businessAccountRef.get("id");
                sourceField = "instagram_business_account";
                log.info("[OAuth] Found IG via instagram_business_account: ig_id={}", igAccountId);
            }

            // If not found, check connected_instagram_account (alternate field for Creator accounts)
            if (igAccountId == null) {
                Map<String, Object> connectedAccountRef = (Map<String, Object>) pageBody.get("connected_instagram_account");
                if (connectedAccountRef != null && connectedAccountRef.get("id") != null) {
                    igAccountId = (String) connectedAccountRef.get("id");
                    sourceField = "connected_instagram_account";
                    log.info("[OAuth] Found IG via connected_instagram_account: ig_id={}", igAccountId);
                }
            }

            if (igAccountId == null) {
                log.info("[OAuth] NO IG ACCOUNT found for page_id={} (both fields null)", pageId);
                return null;
            }

            // Step 2: Fetch Instagram account details (username, name, profile picture)
            String igUrl = GRAPH_API_BASE + "/" + igAccountId + "?fields=id,username,name,profile_picture_url&access_token=" + accessToken;
            log.info("[OAuth] FETCHING IG DETAILS: GET /{}?fields=id,username,name,profile_picture_url", igAccountId);

            try {
                ResponseEntity<Map> igResponse = restTemplate.getForEntity(igUrl, Map.class);
                Map<String, Object> igBody = igResponse.getBody();

                log.info("[OAuth] RAW /{} DETAILS RESPONSE: {}", igAccountId, igBody);

                InstagramAccount account = new InstagramAccount();
                account.id = igAccountId;
                account.sourceField = sourceField;

                if (igBody != null) {
                    account.username = (String) igBody.get("username");
                    account.name = (String) igBody.get("name");
                    account.profilePictureUrl = (String) igBody.get("profile_picture_url");
                }

                log.info("[OAuth] IG ACCOUNT DETAILS: id={}, username={}, source_field={}",
                        account.id, account.username, sourceField);
                return account;
            } catch (RestClientException e) {
                // If we can't get details, still return the account with just the ID
                log.warn("[OAuth] Failed to fetch IG details for {}: {} (still returning account)", igAccountId, e.getMessage());
                InstagramAccount account = new InstagramAccount();
                account.id = igAccountId;
                account.sourceField = sourceField;
                return account;
            }

        } catch (RestClientException e) {
            log.error("[OAuth] FAILED to fetch IG for page_id={}: {}", pageId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Find existing business or create a new one.
     */
    private Business findOrCreateBusiness(User user, String facebookUserId,
                                          FacebookPage page, InstagramAccount igAccount) {
        // First try to find by Instagram Business Account ID
        return businessRepository.findByInstagramBusinessAccountId(igAccount.id)
                .map(existing -> {
                    // Update existing business with latest info
                    existing.setFacebookUserId(facebookUserId);
                    existing.setFacebookPageId(page.id);
                    existing.setName(page.name);
                    if (igAccount.username != null) {
                        existing.setInstagramUsername(igAccount.username);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    // Try to find by Facebook Page ID
                    return businessRepository.findByFacebookPageId(page.id)
                            .map(existing -> {
                                // Update existing business
                                existing.setInstagramBusinessAccountId(igAccount.id);
                                if (igAccount.username != null) {
                                    existing.setInstagramUsername(igAccount.username);
                                }
                                return existing;
                            })
                            .orElseGet(() -> {
                                // Create new business
                                Business newBusiness = new Business();
                                newBusiness.setOwner(user);
                                newBusiness.setName(page.name);
                                newBusiness.setFacebookUserId(facebookUserId);
                                newBusiness.setFacebookPageId(page.id);
                                newBusiness.setInstagramBusinessAccountId(igAccount.id);
                                if (igAccount.username != null) {
                                    newBusiness.setInstagramUsername(igAccount.username);
                                }
                                return newBusiness;
                            });
                });
    }

    /**
     * Simple DTO for Facebook Page data.
     */
    private static class FacebookPage {
        String id;
        String name;
        String accessToken;
    }

    /**
     * Simple DTO for Instagram Business Account data.
     */
    private static class InstagramAccount {
        String id;
        String username;
        String name;
        String profilePictureUrl;
        String sourceField; // "instagram_business_account" or "connected_instagram_account"
    }
}
