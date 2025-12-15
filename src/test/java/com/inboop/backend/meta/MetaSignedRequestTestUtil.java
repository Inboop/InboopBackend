package com.inboop.backend.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Utility class for generating test signed_request payloads.
 *
 * META APP REVIEW NOTE:
 * This class helps you test the data deletion callback locally.
 * It generates signed_request payloads that mimic what Meta sends.
 *
 * IMPORTANT: This is for testing only. In production, only Meta
 * should be generating these signed requests using your app secret.
 *
 * Usage:
 * 1. Set your app secret
 * 2. Call generateSignedRequest() with test data
 * 3. POST the result to /meta/data-deletion
 *
 * Example test with curl:
 * <pre>
 * curl -X POST http://localhost:8080/meta/data-deletion \
 *   -H "Content-Type: application/x-www-form-urlencoded" \
 *   -d "signed_request=<generated_value>"
 * </pre>
 */
public class MetaSignedRequestTestUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Generate a signed_request like Meta sends.
     *
     * @param appSecret            Your Meta app secret
     * @param facebookUserId       Test Facebook user ID (e.g., "123456789")
     * @param instagramBusinessId  Optional Instagram business account ID
     * @return Base64url encoded signed_request string
     */
    public static String generateSignedRequest(
            String appSecret,
            String facebookUserId,
            String instagramBusinessId) throws Exception {

        // Build the payload JSON
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("algorithm", "HMAC-SHA256");
        payload.put("user_id", facebookUserId);
        payload.put("issued_at", Instant.now().getEpochSecond());

        if (instagramBusinessId != null) {
            payload.put("instagram_business_account_id", instagramBusinessId);
        }

        // Encode payload to base64url
        String payloadJson = objectMapper.writeValueAsString(payload);
        String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

        // Generate HMAC-SHA256 signature
        byte[] signature = computeHmacSha256(encodedPayload, appSecret);
        String encodedSignature = base64UrlEncode(signature);

        // Combine: signature.payload
        return encodedSignature + "." + encodedPayload;
    }

    /**
     * Generate a test payload for data deletion callback.
     *
     * Example usage:
     * <pre>
     * String signedRequest = MetaSignedRequestTestUtil.generateDataDeletionPayload(
     *     "your_app_secret",
     *     "fb_user_123",
     *     "ig_business_456"
     * );
     * </pre>
     */
    public static String generateDataDeletionPayload(
            String appSecret,
            String facebookUserId,
            String instagramBusinessId) throws Exception {

        return generateSignedRequest(appSecret, facebookUserId, instagramBusinessId);
    }

    /**
     * Print a curl command for testing locally.
     */
    public static void printCurlCommand(String signedRequest, String baseUrl) {
        System.out.println("\n=== Test Data Deletion Callback ===");
        System.out.println("Copy and run this curl command:\n");
        System.out.println("curl -X POST " + baseUrl + "/meta/data-deletion \\");
        System.out.println("  -H \"Content-Type: application/x-www-form-urlencoded\" \\");
        System.out.println("  -d \"signed_request=" + signedRequest + "\"");
        System.out.println("\n=================================\n");
    }

    /**
     * Main method for generating test payloads.
     *
     * Run with:
     * mvn exec:java -Dexec.mainClass="com.inboop.backend.meta.MetaSignedRequestTestUtil" \
     *   -Dexec.args="YOUR_APP_SECRET fb_user_123 ig_business_456"
     */
    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.out.println("Usage: MetaSignedRequestTestUtil <app_secret> <facebook_user_id> [instagram_business_id]");
                System.out.println("\nExample:");
                System.out.println("  java MetaSignedRequestTestUtil abc123secret fb_user_123 ig_business_456");
                return;
            }

            String appSecret = args[0];
            String facebookUserId = args[1];
            String instagramBusinessId = args.length > 2 ? args[2] : null;

            String signedRequest = generateSignedRequest(appSecret, facebookUserId, instagramBusinessId);

            System.out.println("\nGenerated signed_request:");
            System.out.println(signedRequest);

            printCurlCommand(signedRequest, "http://localhost:8080");

            // Also print for production URL
            printCurlCommand(signedRequest, "https://api.inboop.com");

        } catch (Exception e) {
            System.err.println("Error generating signed_request: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static byte[] computeHmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(secretKey);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
