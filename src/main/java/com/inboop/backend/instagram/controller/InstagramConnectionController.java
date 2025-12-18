package com.inboop.backend.instagram.controller;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.auth.repository.UserRepository;
import com.inboop.backend.business.entity.Business;
import com.inboop.backend.business.repository.BusinessRepository;
import com.inboop.backend.instagram.dto.IntegrationStatusResponse;
import com.inboop.backend.instagram.service.InstagramIntegrationCheckService;
import com.inboop.backend.instagram.util.SecureCookieUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controller for Instagram Business account connection.
 *
 * Flow:
 * 1. POST /api/v1/integrations/instagram/connect (authenticated) - Returns connection token
 * 2. Frontend redirects to GET /instagram/connect/start?token=xxx (public endpoint)
 * 3. Backend sets cookie, redirects to Facebook OAuth
 * 4. User authorizes on Facebook
 * 5. FacebookOAuth2SuccessHandler reads cookie, stores token, redirects to frontend
 */
@RestController
public class InstagramConnectionController {

    private static final Logger log = LoggerFactory.getLogger(InstagramConnectionController.class);
    public static final String CONNECTION_COOKIE_NAME = "ig_connect";

    // Simple in-memory store for connection tokens (userId -> token, token -> userId+timestamp)
    // In production, use Redis or database
    private static final ConcurrentHashMap<String, ConnectionToken> connectionTokens = new ConcurrentHashMap<>();

    private final SecureCookieUtil secureCookieUtil;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final InstagramIntegrationCheckService integrationCheckService;

    @Value("${spring.security.oauth2.client.registration.facebook.client-id:}")
    private String clientId;

    @Value("${app.cookie.secure:true}")
    private boolean secureCookie;

    @Value("${app.frontend.url:https://app.inboop.com}")
    private String frontendUrl;

    public InstagramConnectionController(SecureCookieUtil secureCookieUtil,
                                         UserRepository userRepository,
                                         BusinessRepository businessRepository,
                                         InstagramIntegrationCheckService integrationCheckService) {
        this.secureCookieUtil = secureCookieUtil;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.integrationCheckService = integrationCheckService;
    }

    /**
     * Step 1: Initialize connection (requires JWT auth).
     * Returns a one-time token that can be used to start the OAuth flow.
     *
     * POST /api/v1/integrations/instagram/connect
     */
    @PostMapping("/api/v1/integrations/instagram/connect")
    public ResponseEntity<Map<String, Object>> initializeConnection(
            @AuthenticationPrincipal UserDetails userDetails) {

        if (!isConfigured()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Instagram OAuth not configured"
            ));
        }

        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "User not found"
            ));
        }

        // Generate a one-time connection token
        String token = generateConnectionToken(user.getId());

        log.info("Generated connection token for user ID: {}", user.getId());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "redirectUrl", "/instagram/connect/start?token=" + token
        ));
    }

    /**
     * Step 2: Start OAuth flow (public endpoint - no auth required).
     * Validates the connection token, sets cookie, and redirects to Facebook.
     *
     * GET /instagram/connect/start?token=xxx
     */
    @GetMapping("/instagram/connect/start")
    public void startOAuthFlow(@RequestParam String token,
                               HttpServletResponse response) throws IOException {

        // Validate and consume the connection token
        ConnectionToken connToken = connectionTokens.remove(token);

        if (connToken == null || connToken.isExpired()) {
            log.warn("Invalid or expired connection token: {}", token);
            response.sendRedirect(frontendUrl + "/settings?instagram_error=invalid_token");
            return;
        }

        // Create signed cookie with user ID and timestamp
        String cookieValue = connToken.userId + ":" + System.currentTimeMillis();
        String signedValue = secureCookieUtil.sign(cookieValue);

        Cookie cookie = new Cookie(CONNECTION_COOKIE_NAME, signedValue);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/");
        cookie.setMaxAge(600); // 10 minutes
        cookie.setAttribute("SameSite", "Lax");

        response.addCookie(cookie);

        log.info("Starting Instagram OAuth for user ID: {}", connToken.userId);

        // Redirect to Spring Security's OAuth2 authorization endpoint
        response.sendRedirect("/oauth2/authorization/facebook");
    }

    /**
     * Get Instagram integration status with real-time checks.
     *
     * This endpoint performs comprehensive checks:
     * - Verifies token validity
     * - Checks Facebook Pages accessibility
     * - Verifies Instagram Business Account linkage
     * - Detects permission issues and cooldown periods
     *
     * GET /api/v1/integrations/instagram/status
     */
    @GetMapping("/api/v1/integrations/instagram/status")
    public ResponseEntity<IntegrationStatusResponse> getIntegrationStatus(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

        if (user == null) {
            return ResponseEntity.ok(IntegrationStatusResponse.notConnected());
        }

        // Perform comprehensive integration check
        IntegrationStatusResponse status = integrationCheckService.checkStatus(user);

        log.info("Integration status check for user {}: {}", user.getId(), status.getStatus());

        return ResponseEntity.ok(status);
    }

    /**
     * Get basic Instagram connection status (lightweight check, no API calls).
     *
     * GET /api/v1/integrations/instagram/status/basic
     */
    @GetMapping("/api/v1/integrations/instagram/status/basic")
    public ResponseEntity<Map<String, Object>> getBasicConnectionStatus(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

        if (user == null) {
            return ResponseEntity.ok(Map.of(
                    "connected", false,
                    "configured", isConfigured()
            ));
        }

        // Find user's active businesses with Instagram connected
        List<Business> businesses = businessRepository.findByOwnerId(user.getId());
        List<Business> connectedBusinesses = businesses.stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()) && b.getAccessToken() != null && b.getInstagramBusinessAccountId() != null)
                .toList();

        if (!connectedBusinesses.isEmpty()) {
            Business primary = connectedBusinesses.get(0);
            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "configured", isConfigured(),
                    "count", connectedBusinesses.size(),
                    "instagramBusinessAccountId", primary.getInstagramBusinessAccountId(),
                    "instagramUsername", primary.getInstagramUsername() != null ? primary.getInstagramUsername() : "",
                    "facebookPageId", primary.getFacebookPageId() != null ? primary.getFacebookPageId() : "",
                    "businessName", primary.getName() != null ? primary.getName() : ""
            ));
        }

        return ResponseEntity.ok(Map.of(
                "connected", false,
                "configured", isConfigured()
        ));
    }

    /**
     * Disconnect Instagram account.
     *
     * DELETE /api/v1/integrations/instagram/disconnect
     */
    @DeleteMapping("/api/v1/integrations/instagram/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "User not found"
            ));
        }

        // Find and deactivate user's businesses
        List<Business> businesses = businessRepository.findByOwnerId(user.getId());
        int disconnected = 0;

        for (Business business : businesses) {
            if (business.getAccessToken() != null) {
                // Revoke the token with Facebook (best practice)
                revokeAccessToken(business.getAccessToken());

                business.setAccessToken(null);
                business.setTokenExpiresAt(null);
                business.setIsActive(false);
                businessRepository.save(business);
                disconnected++;
            }
        }

        log.info("Disconnected {} Instagram account(s) for user ID: {}", disconnected, user.getId());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "disconnected", disconnected
        ));
    }

    /**
     * Disconnect a specific Instagram business account by ID.
     *
     * DELETE /api/v1/integrations/instagram/disconnect/{businessId}
     */
    @DeleteMapping("/api/v1/integrations/instagram/disconnect/{businessId}")
    public ResponseEntity<Map<String, Object>> disconnectById(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long businessId) {

        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "User not found"
            ));
        }

        // Find the specific business
        Business business = businessRepository.findById(businessId).orElse(null);

        if (business == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Business not found"
            ));
        }

        // Verify ownership
        if (!business.getOwner().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "error", "Not authorized to disconnect this account"
            ));
        }

        if (business.getAccessToken() == null && !Boolean.TRUE.equals(business.getIsActive())) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Account already disconnected"
            ));
        }

        // Revoke the token with Facebook (best practice)
        if (business.getAccessToken() != null) {
            revokeAccessToken(business.getAccessToken());
        }

        business.setAccessToken(null);
        business.setTokenExpiresAt(null);
        business.setIsActive(false);
        businessRepository.save(business);

        log.info("Disconnected Instagram account {} for user ID: {}", businessId, user.getId());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "disconnectedBusinessId", businessId,
                "businessName", business.getName() != null ? business.getName() : ""
        ));
    }

    /**
     * Revoke an access token with Facebook's API.
     * This is a best practice - ensures the token can't be used even if leaked.
     */
    private void revokeAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            return;
        }

        try {
            String url = "https://graph.facebook.com/v21.0/me/permissions?access_token=" + accessToken;
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            restTemplate.delete(url);
            log.debug("Successfully revoked Facebook access token");
        } catch (Exception e) {
            // Log but don't fail - token may already be invalid
            log.warn("Failed to revoke Facebook access token: {}", e.getMessage());
        }
    }

    private boolean isConfigured() {
        return clientId != null && !clientId.isEmpty() && !clientId.equals("placeholder");
    }

    private String generateConnectionToken(Long userId) {
        // Clean up expired tokens periodically
        connectionTokens.entrySet().removeIf(e -> e.getValue().isExpired());

        String token = java.util.UUID.randomUUID().toString();
        connectionTokens.put(token, new ConnectionToken(userId, System.currentTimeMillis()));
        return token;
    }

    private static class ConnectionToken {
        final Long userId;
        final long createdAt;

        ConnectionToken(Long userId, long createdAt) {
            this.userId = userId;
            this.createdAt = createdAt;
        }

        boolean isExpired() {
            // Token expires after 5 minutes
            return System.currentTimeMillis() - createdAt > 300_000;
        }
    }
}
