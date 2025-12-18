package com.inboop.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Custom OAuth2AuthorizationRequestResolver that adds config_id and auth_type to Facebook authorization requests.
 *
 * This is required for Instagram Business Login which uses a configuration ID
 * to define the permissions and settings for the OAuth flow.
 *
 * auth_type=rerequest forces Meta to re-prompt for declined permissions.
 */
public class FacebookOAuth2AuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver defaultResolver;
    private final String facebookConfigId;

    /**
     * Meta's auth_type parameter values:
     * - "rerequest": Forces re-prompt for declined permissions
     * - "reauthenticate": Forces login even if already logged in (more disruptive)
     *
     * We use "rerequest" to ensure users can grant permissions they may have
     * previously declined, without forcing a full re-authentication.
     */
    private static final String AUTH_TYPE_REREQUEST = "rerequest";

    public FacebookOAuth2AuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            String authorizationRequestBaseUri,
            String facebookConfigId) {
        this.defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, authorizationRequestBaseUri);
        this.facebookConfigId = facebookConfigId;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest authorizationRequest = defaultResolver.resolve(request);
        return customizeAuthorizationRequest(authorizationRequest);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest authorizationRequest = defaultResolver.resolve(request, clientRegistrationId);
        return customizeAuthorizationRequest(authorizationRequest);
    }

    private OAuth2AuthorizationRequest customizeAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null) {
            return null;
        }

        // Only add custom parameters for Facebook registration
        String registrationId = authorizationRequest.getAttribute("registration_id");
        if (!"facebook".equals(registrationId)) {
            // Check if the authorization URI contains facebook.com
            String authUri = authorizationRequest.getAuthorizationUri();
            if (authUri == null || !authUri.contains("facebook.com")) {
                return authorizationRequest;
            }
        }

        // Add config_id and auth_type to additional parameters
        Map<String, Object> additionalParameters = new HashMap<>(authorizationRequest.getAdditionalParameters());
        additionalParameters.put("config_id", facebookConfigId);
        additionalParameters.put("auth_type", AUTH_TYPE_REREQUEST);

        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(additionalParameters)
                .build();
    }
}
