# Meta User Data Deletion Implementation

This document describes the implementation of Meta's User Data Deletion requirements for the Inboop application.

## Overview

Meta requires all apps using Facebook/Instagram APIs to implement data deletion callbacks. When a user requests deletion of their data through Facebook or Instagram settings, Meta calls your app's Data Deletion Callback URL.

## Endpoints

### 1. Data Deletion Callback

**URL:** `POST /meta/data-deletion`

**Purpose:** Receives deletion requests from Meta when a user requests their data to be deleted.

**Request Format:**
```
Content-Type: application/x-www-form-urlencoded
Body: signed_request=<base64url_encoded_payload>
```

**Response Format (Required by Meta):**
```json
{
  "url": "https://inboop.com/data-deletion-status?request_id=<uuid>",
  "confirmation_code": "<uuid>"
}
```

### 2. Deauthorization Callback

**URL:** `POST /meta/deauthorize`

**Purpose:** Called when a user removes the app from their Facebook/Instagram account.

**Request Format:** Same as data deletion

**Response:** HTTP 200 with `{"success": true}`

### 3. Deletion Status Check

**URL:** `GET /meta/data-deletion-status?request_id=<confirmation_code>`

**Purpose:** Allows users to check the status of their deletion request.

**Response:**
```json
{
  "confirmation_code": "abc123",
  "status": "COMPLETED",
  "status_description": "Your data has been successfully deleted from our systems.",
  "requested_at": "2025-01-15T10:30:00",
  "completed_at": "2025-01-15T10:30:05",
  "records_deleted": 42
}
```

## Data Handling

### Data That Is Deleted

| Data Type | Table | What's Deleted |
|-----------|-------|----------------|
| Messages | `messages` | All DM content, attachments, sentiment analysis |
| Leads | `leads` | Customer names, Instagram usernames, profile pictures |
| Conversations | `conversations` | Customer handles, profile pictures, message history |
| Lead Labels | `lead_labels` | All labels associated with deleted leads |

### Data That Is Anonymized (Not Deleted)

| Data Type | Table | What's Changed |
|-----------|-------|----------------|
| Orders | `orders` | `customer_name` → 'DELETED', phone/address → null, lead_id → null |
| Business | `businesses` | `access_token` → null, `instagram_username` → 'DELETED', `is_active` → false |

### Data That Is Retained

- Order totals and counts (for business reporting, no PII)
- Aggregated analytics (no PII)
- Deletion request records (audit trail, required for compliance)

## Security

### Signed Request Verification

Meta sends a `signed_request` parameter that contains:
1. An HMAC-SHA256 signature
2. A base64url-encoded JSON payload

The signature is verified using your **App Secret** from Meta for Developers.

**Never trust a request without verifying the signature.**

### Why These Endpoints Are Public

The `/meta/**` endpoints don't require authentication because:
1. Meta's servers call them directly (no user session)
2. Authentication is via HMAC signature verification
3. Invalid signatures are rejected immediately

## Configuration

### Required Environment Variables

```bash
# Your Meta App Secret (from Meta for Developers > App Settings > Basic)
META_APP_SECRET=your_app_secret_here

# Your application's base URL (used in the status URL returned to Meta)
APP_BASE_URL=https://inboop.com
```

### Meta for Developers Configuration

1. Go to [Meta for Developers](https://developers.facebook.com)
2. Select your app
3. Go to **Settings** > **Basic**
4. Scroll to **Data Deletion**
5. Set **Data Deletion Callback URL** to: `https://api.inboop.com/meta/data-deletion`
6. Set **Deauthorization Callback URL** to: `https://api.inboop.com/meta/deauthorize`

## Testing Locally

### Generate Test Payload

Use the test utility to generate a valid signed_request:

```java
// In test code or with mvn exec:java
String signedRequest = MetaSignedRequestTestUtil.generateSignedRequest(
    "your_app_secret",
    "fb_user_123",
    "ig_business_456"
);
```

### Test with cURL

```bash
# Data Deletion
curl -X POST http://localhost:8080/meta/data-deletion \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "signed_request=<generated_value>"

# Check Status
curl http://localhost:8080/meta/data-deletion-status?request_id=<confirmation_code>

# Deauthorization
curl -X POST http://localhost:8080/meta/deauthorize \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "signed_request=<generated_value>"
```

### Run Integration Tests

```bash
./mvnw test -Dtest=DataDeletionIntegrationTest
```

## Example Meta Payload

When Meta calls your Data Deletion Callback URL, the decoded payload looks like:

```json
{
  "algorithm": "HMAC-SHA256",
  "user_id": "123456789",
  "instagram_business_account_id": "17841400000000000",
  "issued_at": 1705312200
}
```

## Database Schema

### data_deletion_requests Table

```sql
CREATE TABLE data_deletion_requests (
    id BIGSERIAL PRIMARY KEY,
    confirmation_code VARCHAR(36) NOT NULL UNIQUE,
    facebook_user_id VARCHAR(255) NOT NULL,
    instagram_business_id VARCHAR(255),
    request_type VARCHAR(20) NOT NULL,  -- DATA_DELETION or DEAUTHORIZE
    status VARCHAR(20) NOT NULL,         -- PENDING, IN_PROGRESS, COMPLETED, FAILED
    requested_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    records_deleted INTEGER DEFAULT 0,
    error_message TEXT,
    raw_payload TEXT,
    request_ip VARCHAR(50)
);
```

## Compliance Notes

### GDPR Article 17 (Right to Erasure)

This implementation satisfies GDPR's "right to be forgotten" by:
- Deleting all personally identifiable information
- Completing deletion within reasonable timeframe
- Providing confirmation to the user
- Maintaining audit trail for compliance verification

### Meta Platform Terms

This implementation satisfies Meta's requirements by:
- Accepting `signed_request` format
- Verifying HMAC-SHA256 signatures
- Returning the required JSON response format
- Providing a status check URL
- Deleting user data within 30 days (we delete immediately)

## Troubleshooting

### "Invalid signed_request" Error

1. Verify `META_APP_SECRET` is set correctly
2. Check that the secret matches your app in Meta for Developers
3. Ensure the request is coming from Meta's servers (not a test with wrong secret)

### Deletion Not Working

1. Check application logs for errors
2. Verify the Instagram Business ID exists in your database
3. Check for foreign key constraint violations

### Status Page Shows "FAILED"

1. Check the `error_message` column in `data_deletion_requests` table
2. Review application logs for the confirmation code
3. Data may need manual cleanup

## Files

| File | Purpose |
|------|---------|
| `meta/controller/MetaWebhookController.java` | REST endpoints |
| `meta/service/DataDeletionService.java` | Deletion logic |
| `meta/util/MetaSignedRequestParser.java` | Signature verification |
| `meta/entity/DataDeletionRequest.java` | Database entity |
| `meta/repository/DataDeletionRequestRepository.java` | Database access |
| `db/migration/V2__add_data_deletion_requests.sql` | Database schema |
