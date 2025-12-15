package com.inboop.backend.meta.service;

import com.inboop.backend.business.repository.BusinessRepository;
import com.inboop.backend.lead.repository.ConversationRepository;
import com.inboop.backend.lead.repository.LeadRepository;
import com.inboop.backend.lead.repository.MessageRepository;
import com.inboop.backend.meta.dto.DataDeletionResponse;
import com.inboop.backend.meta.dto.DeletionStatusResponse;
import com.inboop.backend.meta.entity.DataDeletionRequest;
import com.inboop.backend.meta.enums.DeletionRequestStatus;
import com.inboop.backend.meta.repository.DataDeletionRequestRepository;
import com.inboop.backend.meta.util.MetaSignedRequestParser;
import com.inboop.backend.meta.util.MetaSignedRequestParser.SignedRequestPayload;
import com.inboop.backend.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling Meta data deletion requests.
 *
 * META APP REVIEW NOTE:
 * This service implements Meta's User Data Deletion requirements:
 *
 * 1. DATA DELETION CALLBACK:
 *    - Receives signed_request from Meta when user requests deletion
 *    - Verifies the signature using app secret
 *    - Deletes or anonymizes all user data
 *    - Returns confirmation code and status URL
 *
 * 2. DEAUTHORIZATION CALLBACK:
 *    - Triggered when user removes app permissions
 *    - Revokes stored access tokens
 *    - Triggers same cleanup as data deletion
 *
 * 3. DATA THAT IS DELETED:
 *    - Instagram access tokens (PII - authentication credential)
 *    - Instagram DM messages (PII - conversation content)
 *    - Lead records (PII - customer names, usernames, profile pictures)
 *    - Conversation records (PII - customer handles, profile pictures)
 *
 * 4. DATA THAT IS ANONYMIZED (not deleted):
 *    - Orders: Customer PII is anonymized, but order records kept for accounting
 *    - Business: Access tokens removed, marked inactive, username set to 'DELETED'
 *
 * 5. DATA THAT IS RETAINED:
 *    - Aggregated analytics (no PII)
 *    - Order totals and counts (no PII)
 *    - The deletion request record itself (for audit trail)
 *
 * @see <a href="https://developers.facebook.com/docs/development/create-an-app/app-dashboard/data-deletion-callback">Meta Data Deletion Callback</a>
 */
@Service
public class DataDeletionService {

    private static final Logger log = LoggerFactory.getLogger(DataDeletionService.class);

    private static final String REQUEST_TYPE_DATA_DELETION = "DATA_DELETION";
    private static final String REQUEST_TYPE_DEAUTHORIZE = "DEAUTHORIZE";

    private final MetaSignedRequestParser signedRequestParser;
    private final DataDeletionRequestRepository deletionRequestRepository;
    private final BusinessRepository businessRepository;
    private final ConversationRepository conversationRepository;
    private final LeadRepository leadRepository;
    private final MessageRepository messageRepository;
    private final OrderRepository orderRepository;

    @Value("${app.base-url:https://inboop.com}")
    private String appBaseUrl;

    public DataDeletionService(
            MetaSignedRequestParser signedRequestParser,
            DataDeletionRequestRepository deletionRequestRepository,
            BusinessRepository businessRepository,
            ConversationRepository conversationRepository,
            LeadRepository leadRepository,
            MessageRepository messageRepository,
            OrderRepository orderRepository) {
        this.signedRequestParser = signedRequestParser;
        this.deletionRequestRepository = deletionRequestRepository;
        this.businessRepository = businessRepository;
        this.conversationRepository = conversationRepository;
        this.leadRepository = leadRepository;
        this.messageRepository = messageRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Process a data deletion request from Meta.
     *
     * META APP REVIEW NOTE:
     * This method:
     * 1. Verifies the signed_request signature (SECURITY: prevents spoofed requests)
     * 2. Checks for existing deletion request (IDEMPOTENCY: same user won't create duplicates)
     * 3. Creates deletion record with unique confirmation code
     * 4. Performs actual data deletion
     * 5. Returns Meta-compliant response
     *
     * @param signedRequest The signed_request parameter from Meta
     * @param requestIp     IP address of the request (for audit)
     * @return DataDeletionResponse in Meta's required format, or empty if signature invalid
     */
    @Transactional
    public Optional<DataDeletionResponse> processDataDeletionRequest(String signedRequest, String requestIp) {
        log.info("Processing data deletion request from IP: {}", requestIp);

        // Step 1: Verify and parse the signed request
        Optional<SignedRequestPayload> payloadOpt = signedRequestParser.parseAndVerify(signedRequest);
        if (payloadOpt.isEmpty()) {
            log.warn("Failed to verify signed_request from IP: {}", requestIp);
            return Optional.empty();
        }

        SignedRequestPayload payload = payloadOpt.get();
        String facebookUserId = payload.getUserId();
        String instagramBusinessId = payload.getInstagramBusinessAccountId().orElse(null);

        log.info("Verified deletion request for Facebook user: {}, Instagram business: {}",
                facebookUserId, instagramBusinessId);

        // Step 2: Check for existing pending/in-progress request (idempotency)
        Optional<DataDeletionRequest> existingRequest = deletionRequestRepository
                .findByFacebookUserIdAndStatusIn(
                        facebookUserId,
                        Arrays.asList(DeletionRequestStatus.PENDING, DeletionRequestStatus.IN_PROGRESS)
                );

        if (existingRequest.isPresent()) {
            log.info("Found existing deletion request for user {}: {}",
                    facebookUserId, existingRequest.get().getConfirmationCode());
            return Optional.of(buildResponse(existingRequest.get().getConfirmationCode()));
        }

        // Step 3: Create new deletion request
        String confirmationCode = UUID.randomUUID().toString();
        DataDeletionRequest deletionRequest = createDeletionRequest(
                confirmationCode,
                facebookUserId,
                instagramBusinessId,
                REQUEST_TYPE_DATA_DELETION,
                signedRequest,
                requestIp
        );

        // Step 4: Perform data deletion
        performDeletion(deletionRequest, instagramBusinessId);

        log.info("Data deletion completed for user {}. Confirmation code: {}",
                facebookUserId, confirmationCode);

        return Optional.of(buildResponse(confirmationCode));
    }

    /**
     * Process a deauthorization callback from Meta.
     *
     * META APP REVIEW NOTE:
     * Deauthorization happens when:
     * - User removes the app from their Facebook/Instagram settings
     * - User revokes specific permissions
     *
     * We treat this the same as a data deletion request - all tokens
     * are revoked and user data is deleted.
     *
     * @param signedRequest The signed_request parameter from Meta
     * @param requestIp     IP address of the request (for audit)
     * @return true if processed successfully, false if signature invalid
     */
    @Transactional
    public boolean processDeauthorization(String signedRequest, String requestIp) {
        log.info("Processing deauthorization request from IP: {}", requestIp);

        Optional<SignedRequestPayload> payloadOpt = signedRequestParser.parseAndVerify(signedRequest);
        if (payloadOpt.isEmpty()) {
            log.warn("Failed to verify deauthorization signed_request from IP: {}", requestIp);
            return false;
        }

        SignedRequestPayload payload = payloadOpt.get();
        String facebookUserId = payload.getUserId();
        String instagramBusinessId = payload.getInstagramBusinessAccountId().orElse(null);

        log.info("Processing deauthorization for Facebook user: {}, Instagram business: {}",
                facebookUserId, instagramBusinessId);

        // Create deletion request for audit trail
        String confirmationCode = UUID.randomUUID().toString();
        DataDeletionRequest deletionRequest = createDeletionRequest(
                confirmationCode,
                facebookUserId,
                instagramBusinessId,
                REQUEST_TYPE_DEAUTHORIZE,
                signedRequest,
                requestIp
        );

        // Perform token revocation and data cleanup
        performDeletion(deletionRequest, instagramBusinessId);

        log.info("Deauthorization completed for user {}. Confirmation code: {}",
                facebookUserId, confirmationCode);

        return true;
    }

    /**
     * Get the status of a deletion request.
     *
     * META APP REVIEW NOTE:
     * This is called from the public status page that users see
     * when they click the URL returned in the deletion response.
     *
     * @param confirmationCode The confirmation code from the deletion response
     * @return Status response, or empty if not found
     */
    public Optional<DeletionStatusResponse> getDeletionStatus(String confirmationCode) {
        return deletionRequestRepository.findByConfirmationCode(confirmationCode)
                .map(this::buildStatusResponse);
    }

    /**
     * Create a new deletion request record.
     */
    private DataDeletionRequest createDeletionRequest(
            String confirmationCode,
            String facebookUserId,
            String instagramBusinessId,
            String requestType,
            String rawPayload,
            String requestIp) {

        DataDeletionRequest request = new DataDeletionRequest();
        request.setConfirmationCode(confirmationCode);
        request.setFacebookUserId(facebookUserId);
        request.setInstagramBusinessId(instagramBusinessId);
        request.setRequestType(requestType);
        request.setRawPayload(rawPayload);
        request.setRequestIp(requestIp);
        request.setStatus(DeletionRequestStatus.IN_PROGRESS);

        return deletionRequestRepository.save(request);
    }

    /**
     * Perform the actual data deletion/anonymization.
     *
     * META APP REVIEW NOTE:
     * Deletion order matters due to foreign key constraints:
     * 1. Delete messages (no FK dependencies)
     * 2. Delete leads (referenced by orders, so nullify first)
     * 3. Anonymize orders (need to keep for accounting, just remove PII)
     * 4. Delete conversations (after messages deleted)
     * 5. Anonymize business (keep record, remove tokens/PII)
     *
     * @param request              The deletion request record
     * @param instagramBusinessId  The Instagram business account to delete data for
     */
    private void performDeletion(DataDeletionRequest request, String instagramBusinessId) {
        int totalDeleted = 0;

        try {
            if (instagramBusinessId != null) {
                // Get conversation IDs for efficient message deletion
                List<Long> conversationIds = conversationRepository
                        .findIdsByBusinessInstagramBusinessId(instagramBusinessId);

                // Step 1: Delete messages (most sensitive - actual DM content)
                if (!conversationIds.isEmpty()) {
                    int messagesDeleted = messageRepository.deleteByConversationIds(conversationIds);
                    log.info("Deleted {} messages for business {}", messagesDeleted, instagramBusinessId);
                    totalDeleted += messagesDeleted;
                }

                // Step 2: Anonymize orders (keep records, remove PII)
                int ordersAnonymized = orderRepository.anonymizeByBusinessInstagramBusinessId(instagramBusinessId);
                log.info("Anonymized {} orders for business {}", ordersAnonymized, instagramBusinessId);

                // Step 3: Delete leads (customer PII)
                int leadsDeleted = leadRepository.deleteByBusinessInstagramBusinessId(instagramBusinessId);
                log.info("Deleted {} leads for business {}", leadsDeleted, instagramBusinessId);
                totalDeleted += leadsDeleted;

                // Step 4: Delete conversations (customer handles, names)
                int conversationsDeleted = conversationRepository.deleteByBusinessInstagramBusinessId(instagramBusinessId);
                log.info("Deleted {} conversations for business {}", conversationsDeleted, instagramBusinessId);
                totalDeleted += conversationsDeleted;

                // Step 5: Anonymize business (remove tokens, mark inactive)
                int businessesAnonymized = businessRepository.anonymizeByInstagramBusinessId(instagramBusinessId);
                log.info("Anonymized {} businesses for Instagram ID {}", businessesAnonymized, instagramBusinessId);
            }

            // Mark request as completed
            request.setStatus(DeletionRequestStatus.COMPLETED);
            request.setCompletedAt(LocalDateTime.now());
            request.setRecordsDeleted(totalDeleted);
            deletionRequestRepository.save(request);

            log.info("Deletion completed. Total records deleted/anonymized: {}", totalDeleted);

        } catch (Exception e) {
            log.error("Error during data deletion for request {}: {}",
                    request.getConfirmationCode(), e.getMessage(), e);

            request.setStatus(DeletionRequestStatus.FAILED);
            request.setErrorMessage(e.getMessage());
            deletionRequestRepository.save(request);

            throw e; // Re-throw to trigger transaction rollback
        }
    }

    /**
     * Build the Meta-compliant deletion response.
     */
    private DataDeletionResponse buildResponse(String confirmationCode) {
        String statusUrl = appBaseUrl + "/data-deletion-status?request_id=" + confirmationCode;
        return new DataDeletionResponse(statusUrl, confirmationCode);
    }

    /**
     * Build a status response from a deletion request.
     */
    private DeletionStatusResponse buildStatusResponse(DataDeletionRequest request) {
        DeletionStatusResponse response = new DeletionStatusResponse();
        response.setConfirmationCode(request.getConfirmationCode());
        response.setStatus(request.getStatus().name());
        response.setStatusDescription(getStatusDescription(request.getStatus()));
        response.setRequestedAt(request.getRequestedAt());
        response.setCompletedAt(request.getCompletedAt());
        response.setRecordsDeleted(request.getRecordsDeleted());
        return response;
    }

    /**
     * Get human-readable status description.
     */
    private String getStatusDescription(DeletionRequestStatus status) {
        return switch (status) {
            case PENDING -> "Your data deletion request has been received and is queued for processing.";
            case IN_PROGRESS -> "Your data is currently being deleted from our systems.";
            case COMPLETED -> "Your data has been successfully deleted from our systems.";
            case FAILED -> "There was an error processing your request. Please contact support.";
        };
    }
}
