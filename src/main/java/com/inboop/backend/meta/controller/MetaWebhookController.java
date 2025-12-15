package com.inboop.backend.meta.controller;

import com.inboop.backend.meta.dto.DataDeletionResponse;
import com.inboop.backend.meta.dto.DeletionStatusResponse;
import com.inboop.backend.meta.service.DataDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Controller for Meta (Facebook/Instagram) webhook callbacks.
 *
 * META APP REVIEW NOTE:
 * This controller implements the required endpoints for Meta App Review:
 *
 * 1. POST /meta/data-deletion
 *    - Data Deletion Callback URL
 *    - Called by Meta when user requests deletion of their data
 *    - Must return JSON with url and confirmation_code
 *
 * 2. POST /meta/deauthorize
 *    - Deauthorization Callback URL
 *    - Called by Meta when user removes the app
 *    - Should revoke tokens and cleanup user data
 *
 * 3. GET /meta/data-deletion-status
 *    - Public status page for users to check deletion progress
 *    - URL is returned to Meta in the deletion response
 *
 * SECURITY NOTE:
 * These endpoints do NOT require authentication because:
 * - They are called directly by Meta's servers
 * - Authentication is via signed_request signature verification
 * - The signature uses your app secret which only you and Meta know
 *
 * @see <a href="https://developers.facebook.com/docs/development/create-an-app/app-dashboard/data-deletion-callback">Meta Data Deletion Callback</a>
 */
@RestController
@RequestMapping("/meta")
public class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);

    private final DataDeletionService dataDeletionService;

    public MetaWebhookController(DataDeletionService dataDeletionService) {
        this.dataDeletionService = dataDeletionService;
    }

    /**
     * Data Deletion Callback endpoint.
     *
     * META APP REVIEW NOTE:
     * This endpoint receives POST requests from Meta when a user:
     * 1. Goes to Facebook Settings > Apps and Websites
     * 2. Selects your app and clicks "Remove"
     * 3. Chooses to delete their data
     *
     * Request format:
     * Content-Type: application/x-www-form-urlencoded
     * Body: signed_request=<base64url_encoded_payload>
     *
     * Required response format (HTTP 200):
     * {
     *   "url": "https://example.com/deletion-status?id=abc123",
     *   "confirmation_code": "abc123"
     * }
     *
     * @param signedRequest The signed_request parameter from Meta
     * @param request       HttpServletRequest for getting client IP
     * @return DataDeletionResponse in Meta's required format
     */
    @PostMapping(value = "/data-deletion",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handleDataDeletion(
            @RequestParam("signed_request") String signedRequest,
            HttpServletRequest request) {

        String clientIp = getClientIp(request);
        log.info("Received data deletion callback from IP: {}", clientIp);

        Optional<DataDeletionResponse> response = dataDeletionService
                .processDataDeletionRequest(signedRequest, clientIp);

        if (response.isEmpty()) {
            // META APP REVIEW NOTE:
            // Return 400 Bad Request for invalid signatures.
            // This prevents malicious actors from triggering false deletions.
            log.warn("Rejected data deletion request: invalid signed_request");
            return ResponseEntity.badRequest()
                    .body("{\"error\": \"Invalid signed_request\"}");
        }

        return ResponseEntity.ok(response.get());
    }

    /**
     * Deauthorization Callback endpoint.
     *
     * META APP REVIEW NOTE:
     * This endpoint receives POST requests from Meta when a user
     * removes your app from their Facebook/Instagram account.
     *
     * Unlike data deletion, deauthorization may not require data deletion,
     * but we choose to delete/anonymize all data for privacy compliance.
     *
     * The response doesn't have a required format - Meta just expects HTTP 200.
     *
     * @param signedRequest The signed_request parameter from Meta
     * @param request       HttpServletRequest for getting client IP
     * @return HTTP 200 on success, 400 on invalid signature
     */
    @PostMapping(value = "/deauthorize",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handleDeauthorization(
            @RequestParam("signed_request") String signedRequest,
            HttpServletRequest request) {

        String clientIp = getClientIp(request);
        log.info("Received deauthorization callback from IP: {}", clientIp);

        boolean success = dataDeletionService.processDeauthorization(signedRequest, clientIp);

        if (!success) {
            log.warn("Rejected deauthorization request: invalid signed_request");
            return ResponseEntity.badRequest()
                    .body("{\"error\": \"Invalid signed_request\"}");
        }

        // META APP REVIEW NOTE:
        // Return 200 OK to acknowledge receipt.
        // No specific response body is required.
        return ResponseEntity.ok()
                .body("{\"success\": true}");
    }

    /**
     * Data Deletion Status endpoint.
     *
     * META APP REVIEW NOTE:
     * This is the public endpoint that users visit to check their deletion status.
     * The URL to this endpoint is returned to Meta in the data deletion response.
     *
     * Users will see this URL in their Facebook/Instagram settings after
     * requesting data deletion, allowing them to track the request.
     *
     * @param requestId The confirmation code from the deletion response
     * @return Status information about the deletion request
     */
    @GetMapping(value = "/data-deletion-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getDeletionStatus(
            @RequestParam("request_id") String requestId) {

        log.info("Status check for deletion request: {}", requestId);

        Optional<DeletionStatusResponse> status = dataDeletionService.getDeletionStatus(requestId);

        if (status.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("{\"error\": \"Deletion request not found\"}");
        }

        return ResponseEntity.ok(status.get());
    }

    /**
     * Extract client IP address from the request.
     * Handles proxy headers (X-Forwarded-For) for accurate logging.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs; the first is the original client
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
