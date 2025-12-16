package com.inboop.backend.instagram.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Controller for initiating Facebook/Instagram OAuth2 flow.
 *
 * Flow:
 * 1. Frontend calls GET /api/v1/instagram/oauth/authorize
 * 2. User is redirected to Facebook OAuth dialog
 * 3. User authorizes the app
 * 4. Facebook redirects to /login/oauth2/code/facebook with auth code
 * 5. FacebookOAuthCallbackController exchanges code for token
 * 6. User is redirected to frontend with the token
 */
@RestController
@RequestMapping("/api/v1/instagram/oauth")
public class FacebookOAuthController {

    private static final Logger log = LoggerFactory.getLogger(FacebookOAuthController.class);

    @Value("${facebook.app.id:}")
    private String appId;

    @Value("${facebook.app.secret:}")
    private String appSecret;

    @Value("${facebook.oauth.redirect-uri:}")
    private String redirectUri;

    @Value("${facebook.oauth.scopes:instagram_business_basic,instagram_business_manage_messages,instagram_manage_comments}")
    private String scopes;

    @Value("${facebook.oauth.auth-uri:https://www.facebook.com/v21.0/dialog/oauth}")
    private String authUri;

    /**
     * Initiate OAuth flow - redirect user to Facebook login.
     *
     * GET /api/v1/instagram/oauth/authorize
     *
     * Optional query params:
     * - state: CSRF token from frontend (recommended)
     * - redirect: where to redirect after OAuth completes (default: /settings)
     */
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "/settings") String redirect) {

        if (appId == null || appId.isEmpty()) {
            log.error("Facebook App ID is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        // Include redirect path in state for use after callback
        String stateParam = (state != null ? state + "|" : "") + redirect;

        // Let Spring's UriComponentsBuilder handle URL encoding properly
        String authUrl = UriComponentsBuilder.fromHttpUrl(authUri)
                .queryParam("client_id", appId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scopes)
                .queryParam("response_type", "code")
                .queryParam("state", stateParam)
                .build()
                .toUriString();

        log.info("Redirecting to Facebook OAuth: {}", authUrl);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, authUrl)
                .build();
    }

    /**
     * Check if Facebook OAuth is configured.
     *
     * GET /api/v1/instagram/oauth/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOAuthStatus() {
        boolean configured = appId != null && !appId.isEmpty()
                && appSecret != null && !appSecret.isEmpty();

        return ResponseEntity.ok(Map.of(
                "configured", configured,
                "redirectUri", redirectUri != null ? redirectUri : ""
        ));
    }
}
