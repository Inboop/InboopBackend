package com.inboop.backend.meta.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * Utility class for parsing and verifying Meta's signed_request format.
 *
 * META APP REVIEW NOTE:
 * Meta sends data deletion callbacks using a signed_request parameter.
 * The signed_request is a concatenation of a HMAC SHA-256 signature and a base64url encoded JSON payload.
 *
 * Format: {signature}.{payload}
 *
 * Security:
 * 1. The signature is generated using your app's secret key
 * 2. You MUST verify the signature before trusting the payload
 * 3. Rejecting invalid signatures prevents spoofed deletion requests
 *
 * @see <a href="https://developers.facebook.com/docs/facebook-login/security/#signed-request">Meta Signed Request Documentation</a>
 */
@Component
public class MetaSignedRequestParser {

    private static final Logger log = LoggerFactory.getLogger(MetaSignedRequestParser.class);
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String appSecret;

    public MetaSignedRequestParser(@Value("${meta.app.secret:}") String appSecret) {
        this.appSecret = appSecret;
    }

    /**
     * Parsed payload from a signed_request.
     */
    public static class SignedRequestPayload {
        private final String userId;
        private final String algorithm;
        private final Long issuedAt;
        private final JsonNode rawJson;

        public SignedRequestPayload(String userId, String algorithm, Long issuedAt, JsonNode rawJson) {
            this.userId = userId;
            this.algorithm = algorithm;
            this.issuedAt = issuedAt;
            this.rawJson = rawJson;
        }

        public String getUserId() {
            return userId;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public Long getIssuedAt() {
            return issuedAt;
        }

        public JsonNode getRawJson() {
            return rawJson;
        }

        /**
         * Gets the Instagram Business Account ID if present.
         * This field is included when the deletion is for an Instagram business account.
         */
        public Optional<String> getInstagramBusinessAccountId() {
            if (rawJson.has("instagram_business_account_id")) {
                return Optional.of(rawJson.get("instagram_business_account_id").asText());
            }
            return Optional.empty();
        }
    }

    /**
     * Parse and verify a signed_request from Meta.
     *
     * META APP REVIEW NOTE:
     * This method performs cryptographic verification of the request.
     * - Returns Optional.empty() if verification fails (invalid signature, malformed data, etc.)
     * - NEVER trust the payload without successful verification
     *
     * @param signedRequest The signed_request string from Meta
     * @return Optional containing the parsed payload if valid, empty if invalid
     */
    public Optional<SignedRequestPayload> parseAndVerify(String signedRequest) {
        if (signedRequest == null || signedRequest.isEmpty()) {
            log.warn("Received empty signed_request");
            return Optional.empty();
        }

        if (appSecret == null || appSecret.isEmpty()) {
            log.error("META_APP_SECRET is not configured. Cannot verify signed requests.");
            return Optional.empty();
        }

        // Split the signed_request into signature and payload
        String[] parts = signedRequest.split("\\.", 2);
        if (parts.length != 2) {
            log.warn("Invalid signed_request format: expected 2 parts, got {}", parts.length);
            return Optional.empty();
        }

        String encodedSignature = parts[0];
        String encodedPayload = parts[1];

        try {
            // Decode the signature (Meta uses base64url encoding)
            byte[] signature = base64UrlDecode(encodedSignature);

            // Compute expected signature
            byte[] expectedSignature = computeHmacSha256(encodedPayload, appSecret);

            // Compare signatures (constant-time comparison to prevent timing attacks)
            if (!constantTimeEquals(signature, expectedSignature)) {
                log.warn("Signature verification failed for signed_request");
                return Optional.empty();
            }

            // Decode and parse the payload
            String payloadJson = new String(base64UrlDecode(encodedPayload), StandardCharsets.UTF_8);
            JsonNode payload = objectMapper.readTree(payloadJson);

            // Verify the algorithm
            String algorithm = payload.has("algorithm") ? payload.get("algorithm").asText() : null;
            if (!"HMAC-SHA256".equals(algorithm)) {
                log.warn("Unsupported algorithm: {}", algorithm);
                return Optional.empty();
            }

            // Extract required fields
            String userId = payload.has("user_id") ? payload.get("user_id").asText() : null;
            Long issuedAt = payload.has("issued_at") ? payload.get("issued_at").asLong() : null;

            if (userId == null) {
                log.warn("Missing user_id in signed_request payload");
                return Optional.empty();
            }

            log.info("Successfully verified signed_request for user_id: {}", userId);
            return Optional.of(new SignedRequestPayload(userId, algorithm, issuedAt, payload));

        } catch (Exception e) {
            log.error("Error parsing signed_request: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Compute HMAC-SHA256 signature.
     */
    private byte[] computeHmacSha256(String data, String secret) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(secretKey);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode base64url (URL-safe Base64 without padding).
     * Meta uses base64url encoding which differs from standard base64:
     * - Uses '-' instead of '+'
     * - Uses '_' instead of '/'
     * - May omit padding '='
     */
    private byte[] base64UrlDecode(String input) {
        // Add padding if needed
        String padded = input;
        int paddingNeeded = (4 - (input.length() % 4)) % 4;
        padded = input + "=".repeat(paddingNeeded);

        // Convert from base64url to standard base64
        String base64 = padded.replace('-', '+').replace('_', '/');

        return Base64.getDecoder().decode(base64);
    }

    /**
     * Constant-time comparison of two byte arrays.
     * This prevents timing attacks where an attacker could determine
     * how many bytes of the signature match by measuring response time.
     *
     * META APP REVIEW NOTE:
     * Always use constant-time comparison for cryptographic values.
     */
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
