-- Flyway baseline migration for Inboop Backend
-- This migration creates all tables from scratch for new environments
-- For existing databases, use flyway baseline to mark this as already applied

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'USER',
    oauth_provider VARCHAR(50),
    oauth_id VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Businesses table
CREATE TABLE IF NOT EXISTS businesses (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    instagram_business_id VARCHAR(255) UNIQUE,
    instagram_page_id VARCHAR(255),
    instagram_username VARCHAR(255),
    access_token VARCHAR(1000),
    token_expires_at TIMESTAMP,
    owner_id BIGINT NOT NULL REFERENCES users(id),
    webhook_verified BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_businesses_owner_id ON businesses(owner_id);

-- Conversations table
CREATE TABLE IF NOT EXISTS conversations (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    instagram_conversation_id VARCHAR(255) UNIQUE,
    channel VARCHAR(50) NOT NULL DEFAULT 'INSTAGRAM',
    customer_handle VARCHAR(255),
    customer_name VARCHAR(255),
    profile_picture VARCHAR(500),
    last_message TEXT,
    unread_count INTEGER DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    started_at TIMESTAMP,
    last_message_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversations_business_id ON conversations(business_id);

-- Leads table
CREATE TABLE IF NOT EXISTS leads (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    conversation_id BIGINT REFERENCES conversations(id),
    instagram_user_id VARCHAR(255) NOT NULL,
    instagram_username VARCHAR(255),
    customer_name VARCHAR(255),
    profile_picture VARCHAR(500),
    channel VARCHAR(50) NOT NULL DEFAULT 'INSTAGRAM',
    status VARCHAR(50) NOT NULL DEFAULT 'NEW',
    type VARCHAR(50) NOT NULL DEFAULT 'OTHER',
    assigned_to BIGINT REFERENCES users(id),
    detected_language VARCHAR(10),
    value DECIMAL(10,2),
    notes TEXT,
    last_message_snippet VARCHAR(500),
    last_message_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_leads_business_id ON leads(business_id);
CREATE INDEX IF NOT EXISTS idx_leads_conversation_id ON leads(conversation_id);
CREATE INDEX IF NOT EXISTS idx_leads_assigned_to ON leads(assigned_to);

-- Lead labels (collection table)
CREATE TABLE IF NOT EXISTS lead_labels (
    lead_id BIGINT NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    label VARCHAR(255) NOT NULL,
    PRIMARY KEY (lead_id, label)
);

-- Messages table
CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id),
    instagram_message_id VARCHAR(255) UNIQUE,
    sender_id VARCHAR(255) NOT NULL,
    is_from_customer BOOLEAN NOT NULL,
    original_text TEXT,
    translated_text TEXT,
    detected_language VARCHAR(10),
    sentiment VARCHAR(50),
    ai_classification TEXT,
    has_attachment BOOLEAN DEFAULT FALSE,
    attachment_url VARCHAR(1000),
    is_read BOOLEAN DEFAULT FALSE,
    sent_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_id ON messages(conversation_id);

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(255) NOT NULL UNIQUE,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    lead_id BIGINT REFERENCES leads(id),
    customer_name VARCHAR(255),
    customer_phone VARCHAR(50),
    customer_address TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(10,2),
    notes TEXT,
    tracking_number VARCHAR(255),
    order_date TIMESTAMP NOT NULL,
    shipped_at TIMESTAMP,
    delivered_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_orders_business_id ON orders(business_id);
CREATE INDEX IF NOT EXISTS idx_orders_lead_id ON orders(lead_id);
