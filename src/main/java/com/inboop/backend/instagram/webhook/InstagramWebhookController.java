package com.inboop.backend.instagram.webhook;

import com.inboop.backend.instagram.dto.WebhookPayload;
import com.inboop.backend.shared.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/instagram")
public class
InstagramWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(InstagramWebhookController.class);

    @Value("${instagram.webhook.verify-token:inboop_verify_token}")
    private String verifyToken;

    /**
     * Webhook verification endpoint for Instagram
     * GET /api/v1/webhooks/instagram
     */
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {

        logger.info("Webhook verification request received");

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            logger.info("Webhook verified successfully");
            return ResponseEntity.ok(challenge);
        } else {
            logger.warn("Webhook verification failed");
            return ResponseEntity.status(403).body("Forbidden");
        }
    }

    /**
     * Webhook endpoint to receive Instagram messages
     * POST /api/v1/webhooks/instagram
     */
    @PostMapping
    public ResponseEntity<ApiResponse<String>> receiveWebhook(@RequestBody WebhookPayload payload) {
        logger.info("Received webhook payload: object={}", payload.getObject());

        try {
            // TODO: Process webhook asynchronously using message queue
            if ("instagram".equals(payload.getObject()) && payload.getEntry() != null) {
                payload.getEntry().forEach(entry -> {
                    if (entry.getMessaging() != null) {
                        entry.getMessaging().forEach(messaging -> {
                            String senderId = messaging.getSender().getId();
                            String recipientId = messaging.getRecipient().getId();
                            String messageText = messaging.getMessage() != null ?
                                    messaging.getMessage().getText() : null;

                            logger.info("Message from {} to {}: {}",
                                    senderId, recipientId, messageText);

                            // TODO: Queue message for processing
                            // - Detect language
                            // - Classify intent using AI
                            // - Create/update lead
                            // - Save message
                            // - Send real-time notification via WebSocket
                        });
                    }
                });
            }

            return ResponseEntity.ok(ApiResponse.success("Webhook received"));
        } catch (Exception e) {
            logger.error("Error processing webhook", e);
            return ResponseEntity.ok(ApiResponse.success("Webhook received")); // Always return 200
        }
    }
}
