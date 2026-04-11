package com.inboop.backend.instagram.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboop.backend.instagram.dto.WebhookPayload;
import com.inboop.backend.instagram.entity.WebhookLog;
import com.inboop.backend.instagram.repository.WebhookLogRepository;
import com.inboop.backend.instagram.service.InstagramWebhookService;
import com.inboop.backend.shared.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/instagram")
public class InstagramWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(InstagramWebhookController.class);

    private final InstagramWebhookService webhookService;
    private final WebhookLogRepository webhookLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${instagram.webhook.verify-token:inboop_verify_token}")
    private String verifyToken;

    public InstagramWebhookController(InstagramWebhookService webhookService,
                                       WebhookLogRepository webhookLogRepository,
                                       ObjectMapper objectMapper) {
        this.webhookService = webhookService;
        this.webhookLogRepository = webhookLogRepository;
        this.objectMapper = objectMapper;
    }

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
     *
     * IMPORTANT: Always return 200 OK to Meta, even if processing fails.
     * Meta will retry failed webhooks, which could cause duplicate processing issues.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<String>> receiveWebhook(@RequestBody WebhookPayload payload) {
        // Log full details for debugging
        if (payload.getEntry() != null && !payload.getEntry().isEmpty()) {
            WebhookPayload.Entry entry = payload.getEntry().get(0);
            String entryId = entry.getId();

            if (entry.getMessaging() != null && !entry.getMessaging().isEmpty()) {
                WebhookPayload.Messaging messaging = entry.getMessaging().get(0);
                String senderId = messaging.getSender() != null ? messaging.getSender().getId() : "null";
                String recipientId = messaging.getRecipient() != null ? messaging.getRecipient().getId() : "null";
                String messageText = messaging.getMessage() != null ? messaging.getMessage().getText() : "[no text]";

                logger.info("=== WEBHOOK RECEIVED ===");
                logger.info("Entry ID (Business): {}", entryId);
                logger.info("Sender ID: {}", senderId);
                logger.info("Recipient ID: {}", recipientId);
                logger.info("Message: {}", messageText);
                logger.info("========================");
            } else {
                logger.info("Received webhook: object={}, entryId={}, no messaging data", payload.getObject(), entryId);
            }
        } else {
            logger.info("Received webhook payload: object={}, no entries", payload.getObject());
        }

        // Log the webhook for debugging
        WebhookLog log = createWebhookLog(payload);

        try {
            // Process the webhook synchronously for now
            // TODO: Move to async processing with message queue for high volume
            webhookService.processWebhook(payload);

            log.setProcessed(true);
            webhookLogRepository.save(log);

            return ResponseEntity.ok(ApiResponse.success("Webhook received"));
        } catch (Exception e) {
            logger.error("Error processing webhook", e);
            log.setProcessed(false);
            log.setErrorMessage(e.getMessage());
            webhookLogRepository.save(log);
            // Always return 200 to prevent Meta from retrying
            return ResponseEntity.ok(ApiResponse.success("Webhook received"));
        }
    }

    private WebhookLog createWebhookLog(WebhookPayload payload) {
        WebhookLog log = new WebhookLog();
        log.setObjectType(payload.getObject());

        try {
            log.setRawPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.setRawPayload("Failed to serialize: " + e.getMessage());
        }

        // Extract first entry details if available
        if (payload.getEntry() != null && !payload.getEntry().isEmpty()) {
            WebhookPayload.Entry entry = payload.getEntry().get(0);
            log.setEntryId(entry.getId());

            if (entry.getMessaging() != null && !entry.getMessaging().isEmpty()) {
                WebhookPayload.Messaging messaging = entry.getMessaging().get(0);
                if (messaging.getSender() != null) {
                    log.setSenderId(messaging.getSender().getId());
                }
                if (messaging.getRecipient() != null) {
                    log.setRecipientId(messaging.getRecipient().getId());
                }
                if (messaging.getMessage() != null) {
                    log.setMessageId(messaging.getMessage().getMid());
                    log.setMessageText(messaging.getMessage().getText());
                }
            }
        }

        return webhookLogRepository.save(log);
    }
}
