CREATE SEQUENCE IF NOT EXISTS iptv_sync_hash_seq START WITH 1 INCREMENT BY 50;

-- IPTV sync hash table for tracking content changes
CREATE TABLE iptv_sync_hash
(
    id            BIGINT NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    endpoint_type VARCHAR(50) NOT NULL,
    content_hash  VARCHAR(64) NOT NULL,
    last_checked  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    file_size     BIGINT,
    sync_status   VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    sync_started_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_iptv_sync_hash PRIMARY KEY (id)
);

-- Index for efficient hash lookups
CREATE INDEX idx_iptv_sync_hash_provider_endpoint ON iptv_sync_hash (provider_name, endpoint_type);

-- Unique constraint on provider_name + endpoint_type
ALTER TABLE iptv_sync_hash
    ADD CONSTRAINT uk_iptv_sync_hash_provider_endpoint UNIQUE (provider_name, endpoint_type);

