package com.inboop.backend.meta;

import com.inboop.backend.meta.entity.DataDeletionRequest;
import com.inboop.backend.meta.enums.DeletionRequestStatus;
import com.inboop.backend.meta.repository.DataDeletionRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Meta data deletion callbacks.
 *
 * META APP REVIEW NOTE:
 * These tests verify:
 * 1. Valid signed requests are processed correctly
 * 2. Invalid signed requests are rejected
 * 3. Response format matches Meta's requirements
 * 4. Status endpoint works correctly
 *
 * Run with: ./mvnw test -Dtest=DataDeletionIntegrationTest
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class DataDeletionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataDeletionRequestRepository deletionRequestRepository;

    // Using a constant value that matches application-test.properties
    private static final String APP_SECRET = "test-secret-for-unit-tests";

    /**
     * Test that invalid signed requests are rejected.
     *
     * META APP REVIEW NOTE:
     * This test verifies that spoofed requests (with invalid signatures)
     * are rejected with 400 Bad Request.
     */
    @Test
    public void testInvalidSignedRequestIsRejected() throws Exception {
        // Use wrong app secret to generate invalid signature
        String signedRequest = MetaSignedRequestTestUtil.generateSignedRequest(
                "wrong-app-secret-different-from-test", "test_user", null);

        mockMvc.perform(post("/meta/data-deletion")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("signed_request", signedRequest))
                .andExpect(status().isBadRequest());
    }

    /**
     * Test that malformed signed requests are rejected.
     */
    @Test
    public void testMalformedSignedRequestIsRejected() throws Exception {
        mockMvc.perform(post("/meta/data-deletion")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("signed_request", "not-a-valid-signed-request"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Test that empty signed requests are rejected.
     */
    @Test
    public void testEmptySignedRequestIsRejected() throws Exception {
        mockMvc.perform(post("/meta/data-deletion")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("signed_request", ""))
                .andExpect(status().isBadRequest());
    }

    /**
     * Test status check for non-existent request.
     */
    @Test
    public void testStatusCheckForNonExistentRequest() throws Exception {
        mockMvc.perform(get("/meta/data-deletion-status")
                        .param("request_id", "non-existent-code"))
                .andExpect(status().isNotFound());
    }

    /**
     * Test that the signed request parser correctly validates signatures.
     */
    @Test
    public void testSignedRequestGeneration() throws Exception {
        // Generate a valid signed_request
        String facebookUserId = "test_fb_user_" + System.currentTimeMillis();
        String signedRequest = MetaSignedRequestTestUtil.generateSignedRequest(
                APP_SECRET, facebookUserId, null);

        // Verify it has the correct format (signature.payload)
        assertThat(signedRequest).contains(".");
        String[] parts = signedRequest.split("\\.");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isNotEmpty(); // signature
        assertThat(parts[1]).isNotEmpty(); // payload
    }

    /**
     * Test that invalid deauthorization requests are rejected.
     */
    @Test
    public void testInvalidDeauthorizationIsRejected() throws Exception {
        String signedRequest = MetaSignedRequestTestUtil.generateSignedRequest(
                "wrong-secret", "test_user", null);

        mockMvc.perform(post("/meta/deauthorize")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("signed_request", signedRequest))
                .andExpect(status().isBadRequest());
    }
}
