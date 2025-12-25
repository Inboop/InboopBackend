package com.inboop.backend.instagram.handler;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.auth.repository.UserRepository;
import com.inboop.backend.business.entity.Business;
import com.inboop.backend.instagram.controller.InstagramConnectionController;
import com.inboop.backend.instagram.service.InstagramBusinessService;
import com.inboop.backend.instagram.util.SecureCookieUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Handles successful Facebook/Instagram OAuth2 authentication.
 *
 * Security: Tokens are stored server-side, never sent to frontend.
 *
 * Flow:
 * 1. Read secure cookie to identify which user initiated the connection
 * 2. Fetch Facebook Pages and linked Instagram Business Accounts
 * 3. Store the mapping in Business entities
 * 4. Clear the connection cookie
 * 5. Redirect to frontend with just ?instagram_connected=true
 */
@Component
public class FacebookOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(FacebookOAuth2SuccessHandler.class);

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final SecureCookieUtil secureCookieUtil;
    private final UserRepository userRepository;
    private final InstagramBusinessService instagramBusinessService;

    @Value("${app.frontend.url:https://app.inboop.com}")
    private String frontendUrl;

    public FacebookOAuth2SuccessHandler(OAuth2AuthorizedClientService authorizedClientService,
                                        SecureCookieUtil secureCookieUtil,
                                        UserRepository userRepository,
                                        InstagramBusinessService instagramBusinessService) {
        this.authorizedClientService = authorizedClientService;
        this.secureCookieUtil = secureCookieUtil;
        this.userRepository = userRepository;
        this.instagramBusinessService = instagramBusinessService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            log.error("Unexpected authentication type: {}", authentication.getClass());
            redirectWithError(response, "unexpected_auth_type");
            return;
        }

        String registrationId = oauthToken.getAuthorizedClientRegistrationId();

        if (!"facebook".equals(registrationId)) {
            log.warn("Unexpected OAuth registration: {}", registrationId);
            redirectWithError(response, "wrong_provider");
            return;
        }

        try {
            // Read and verify the connection cookie to identify the user
            Long userId = extractUserIdFromCookie(request);
            if (userId == null) {
                log.error("No valid connection cookie found");
                redirectWithError(response, "session_expired");
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.error("User not found for ID: {}", userId);
                redirectWithError(response, "user_not_found");
                return;
            }

            // Get the access token from OAuth2 authorized client
            OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                    registrationId,
                    oauthToken.getName()
            );

            if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                log.error("No access token found for Facebook OAuth");
                redirectWithError(response, "no_token");
                return;
            }

            String accessToken = authorizedClient.getAccessToken().getTokenValue();
            Instant expiresAt = authorizedClient.getAccessToken().getExpiresAt();
            Instant issuedAt = authorizedClient.getAccessToken().getIssuedAt();

            // Get Facebook user ID from OAuth2User
            OAuth2User oAuth2User = oauthToken.getPrincipal();
            String facebookUserId = oAuth2User.getAttribute("id");

            // INSTRUMENTATION: Log token details for debugging
            String tokenHash = accessToken.length() > 6 ? accessToken.substring(accessToken.length() - 6) : accessToken;
            log.info("[OAuth-Token] FRESH TOKEN RECEIVED: user_id={}, fb_user_id={}, token_hash=...{}, issued_at={}, expires_at={}",
                    userId, facebookUserId, tokenHash, issuedAt, expiresAt);

            log.info("Facebook OAuth successful for user ID: {}, Facebook user ID: {}", userId, facebookUserId);

            // Fetch Facebook Pages and linked Instagram Business Accounts
            List<Business> connectedBusinesses = instagramBusinessService.connectInstagramAccounts(
                    user, facebookUserId, accessToken, expiresAt);

            if (connectedBusinesses.isEmpty()) {
                log.warn("No Instagram Business Accounts found for user ID: {}", userId);
                redirectWithError(response, "no_instagram_accounts");
                return;
            }

            log.info("Successfully connected {} Instagram Business Account(s) for user ID: {}",
                    connectedBusinesses.size(), userId);

            // Clear the connection cookie
            clearConnectionCookie(response);

            // Redirect to frontend with success (no token in URL!)
            response.sendRedirect(frontendUrl + "/settings?instagram_connected=true&count=" + connectedBusinesses.size());

        } catch (Exception e) {
            log.error("Error processing Facebook OAuth success: {}", e.getMessage(), e);
            redirectWithError(response, "connection_failed");
        }
    }

    private Long extractUserIdFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        Cookie connectionCookie = Arrays.stream(cookies)
                .filter(c -> InstagramConnectionController.CONNECTION_COOKIE_NAME.equals(c.getName()))
                .findFirst()
                .orElse(null);

        if (connectionCookie == null) {
            return null;
        }

        String verifiedValue = secureCookieUtil.verify(connectionCookie.getValue());
        if (verifiedValue == null) {
            log.warn("Failed to verify connection cookie signature");
            return null;
        }

        // Cookie value format: userId:timestamp
        String[] parts = verifiedValue.split(":");
        if (parts.length < 2) {
            log.warn("Invalid cookie value format");
            return null;
        }

        try {
            long userId = Long.parseLong(parts[0]);
            long timestamp = Long.parseLong(parts[1]);

            // Check if cookie is expired (10 minutes)
            if (System.currentTimeMillis() - timestamp > 600_000) {
                log.warn("Connection cookie expired");
                return null;
            }

            return userId;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse cookie value: {}", e.getMessage());
            return null;
        }
    }

    private void clearConnectionCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(InstagramConnectionController.CONNECTION_COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0); // Delete the cookie
        response.addCookie(cookie);
    }

    private void redirectWithError(HttpServletResponse response, String error) throws IOException {
        String redirectUrl = frontendUrl + "/settings?instagram_error=" +
                URLEncoder.encode(error, StandardCharsets.UTF_8);
        response.sendRedirect(redirectUrl);
    }
}
