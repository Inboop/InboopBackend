package com.inboop.backend.instagram.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Controller for handling Facebook/Instagram OAuth2 flow.
 *
 * This is used to connect Instagram Business accounts to Inboop.
 * The flow is:
 * 1. User clicks "Connect Instagram" in frontend
 * 2. Frontend redirects to /api/v1/instagram/oauth/authorize
 * 3. User is redirected to Facebook OAuth dialog
 * 4. User authorizes the app
 * 5. Facebook redirects to /login/oauth2/code/facebook with auth code
 * 6. We exchange the code for an access token
 * 7. We redirect back to frontend with the token (or store it)
 */
@RestController
@RequestMapping("/api/v1/instagram/oauth")
public class FacebookOAuthController {

    private static final Logger log = LoggerFactory.getLogger(FacebookOAuthController.class);
    private static final String FACEBOOK_AUTH_URL = "https://www.facebook.com/v21.0/dialog/oauth";
    private static final String FACEBOOK_TOKEN_URL = "https://graph.facebook.com/v21.0/oauth/access_token";

    @Value("${facebook.app.id:}")
    private String appId;

    @Value("${facebook.app.secret:}")
    private String appSecret;

    @Value("${facebook.oauth.redirect-uri:}")
    private String redirectUri;

    @Value("${facebook.oauth.scopes:instagram_basic,instagram_manage_messages,pages_show_list,pages_messaging}")
    private String scopes;

    @Value("${app.frontend.url:https://app.inboop.com}")
    private String frontendUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Initiate OAuth flow - redirect user to Facebook login.
     *
     * GET /api/v1/instagram/oauth/authorize
     *
     * Optional query params:
     * - state: CSRF token from frontend (recommended)
     * - redirect: where to redirect after OAuth completes
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

        String authUrl = UriComponentsBuilder.fromHttpUrl(FACEBOOK_AUTH_URL)
                .queryParam("client_id", appId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scopes)
                .queryParam("response_type", "code")
                .queryParam("state", URLEncoder.encode(stateParam, StandardCharsets.UTF_8))
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
