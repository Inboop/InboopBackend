package com.inboop.backend.instagram.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles the OAuth callback from Facebook.
 *
 * This endpoint is called by Facebook after user authorizes the app.
 * URL: /login/oauth2/code/facebook
 *
 * The flow:
 * 1. Facebook redirects here with ?code=xxx&state=yyy
 * 2. We exchange the code for an access token
 * 3. We redirect to frontend with the token
 */
@RestController
public class FacebookOAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(FacebookOAuthCallbackController.class);
    private static final String FACEBOOK_TOKEN_URL = "https://graph.facebook.com/v21.0/oauth/access_token";
    private static final String FACEBOOK_DEBUG_TOKEN_URL = "https://graph.facebook.com/v21.0/debug_token";

    @Value("${facebook.app.id:}")
    private String appId;

    @Value("${facebook.app.secret:}")
    private String appSecret;

    @Value("${facebook.oauth.redirect-uri:}")
    private String redirectUri;

    @Value("${app.frontend.url:https://app.inboop.com}")
    private String frontendUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * OAuth callback endpoint.
     *
     * GET /login/oauth2/code/facebook?code=xxx&state=yyy
     *
     * On success: Redirects to frontend with access token
     * On error: Redirects to frontend with error
     */
    @GetMapping("/login/oauth2/code/facebook")
    public ResponseEntity<Void> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {

        // Handle error from Facebook
        if (error != null) {
            log.error("Facebook OAuth error: {} - {}", error, errorDescription);
            String errorRedirect = frontendUrl + "/settings?error=" +
                    URLEncoder.encode(errorDescription != null ? errorDescription : error, StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, errorRedirect)
                    .build();
        }

        if (code == null || code.isEmpty()) {
            log.error("No authorization code received from Facebook");
            String errorRedirect = frontendUrl + "/settings?error=no_code";
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, errorRedirect)
                    .build();
        }

        // Parse state to get original redirect path
        String redirectPath = "/settings";
        if (state != null && !state.isEmpty()) {
            try {
                String decodedState = URLDecoder.decode(state, StandardCharsets.UTF_8);
                // State format: "csrf_token|redirect_path" or just "redirect_path"
                if (decodedState.contains("|")) {
                    redirectPath = decodedState.substring(decodedState.lastIndexOf("|") + 1);
                } else {
                    redirectPath = decodedState;
                }
            } catch (Exception e) {
                log.warn("Failed to parse state parameter: {}", e.getMessage());
            }
        }

        try {
            // Exchange code for access token
            String tokenUrl = UriComponentsBuilder.fromHttpUrl(FACEBOOK_TOKEN_URL)
                    .queryParam("client_id", appId)
                    .queryParam("client_secret", appSecret)
                    .queryParam("redirect_uri", redirectUri)
                    .queryParam("code", code)
                    .toUriString();

            log.info("Exchanging authorization code for access token");

            ResponseEntity<String> tokenResponse = restTemplate.getForEntity(tokenUrl, String.class);

            if (!tokenResponse.getStatusCode().is2xxSuccessful()) {
                log.error("Failed to get access token: {}", tokenResponse.getBody());
                String errorRedirect = frontendUrl + redirectPath + "?error=token_exchange_failed";
                return ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, errorRedirect)
                        .build();
            }

            JsonNode tokenJson = objectMapper.readTree(tokenResponse.getBody());
            String accessToken = tokenJson.get("access_token").asText();
            long expiresIn = tokenJson.has("expires_in") ? tokenJson.get("expires_in").asLong() : 0;

            log.info("Successfully obtained access token, expires in {} seconds", expiresIn);

            // TODO: Store the access token in the database associated with the user's business
            // For now, redirect to frontend with the token
            // In production, you should:
            // 1. Get the current authenticated user
            // 2. Fetch their Instagram business accounts using the token
            // 3. Store the token securely in the database
            // 4. Redirect without exposing the token in URL

            String successRedirect = frontendUrl + redirectPath +
                    "?success=true&token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);

            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, successRedirect)
                    .build();

        } catch (Exception e) {
            log.error("Error during OAuth callback: {}", e.getMessage(), e);
            String errorRedirect = frontendUrl + redirectPath + "?error=" +
                    URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, errorRedirect)
                    .build();
        }
    }
}
