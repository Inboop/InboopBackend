-- Flyway migration V2: Add data_deletion_requests table
-- This table tracks Meta data deletion and deauthorization requests
-- Required for Meta App Review: User Data Deletion compliance

-- META APP REVIEW NOTE:
-- This table stores all data deletion requests from Meta's callbacks.
-- It provides:
-- 1. Audit trail for compliance verification
-- 2. Status tracking for user-facing status page
-- 3. Idempotency check to prevent duplicate deletions

CREATE TABLE IF NOT EXISTS data_deletion_requests (
    id BIGSERIAL PRIMARY KEY,

    -- Unique confirmation code returned to Meta
    -- Users can use this to check deletion status
    confirmation_code VARCHAR(36) NOT NULL UNIQUE,

    -- Facebook user ID from the signed_request
    -- This is the primary identifier for the requesting user
    facebook_user_id VARCHAR(255) NOT NULL,

    -- Instagram Business Account ID (optional)
    -- Present when deletion is for an IG business account
    instagram_business_id VARCHAR(255),

    -- Request type: DATA_DELETION or DEAUTHORIZE
    request_type VARCHAR(20) NOT NULL,

    -- Current status of the deletion
    -- PENDING -> IN_PROGRESS -> COMPLETED/FAILED
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- When Meta sent the deletion request
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- When deletion was completed (null until done)
    completed_at TIMESTAMP,

    -- Number of records deleted (for audit)
    records_deleted INTEGER DEFAULT 0,

    -- Error message if deletion failed
    error_message TEXT,

    -- Original signed_request for audit trail
    raw_payload TEXT,

    -- IP address of the request origin
    request_ip VARCHAR(50)
);

-- Index for looking up by confirmation code (status checks)
CREATE INDEX IF NOT EXISTS idx_deletion_confirmation_code
    ON data_deletion_requests(confirmation_code);

-- Index for finding requests by Facebook user (idempotency checks)
CREATE INDEX IF NOT EXISTS idx_deletion_facebook_user_id
    ON data_deletion_requests(facebook_user_id);

-- Index for finding requests by Instagram business ID
CREATE INDEX IF NOT EXISTS idx_deletion_instagram_business_id
    ON data_deletion_requests(instagram_business_id);

-- Index for finding pending requests (batch processing)
CREATE INDEX IF NOT EXISTS idx_deletion_status
    ON data_deletion_requests(status);

-- Comments for documentation
COMMENT ON TABLE data_deletion_requests IS 'Tracks Meta data deletion and deauthorization requests for GDPR/privacy compliance';
COMMENT ON COLUMN data_deletion_requests.confirmation_code IS 'UUID returned to Meta - users can check deletion status with this code';
COMMENT ON COLUMN data_deletion_requests.facebook_user_id IS 'Facebook user ID from Meta signed_request payload';
COMMENT ON COLUMN data_deletion_requests.instagram_business_id IS 'Instagram Business Account ID if deletion is for an IG business';
COMMENT ON COLUMN data_deletion_requests.raw_payload IS 'Original signed_request from Meta for audit trail';
