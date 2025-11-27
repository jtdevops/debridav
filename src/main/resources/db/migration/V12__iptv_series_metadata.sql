CREATE SEQUENCE IF NOT EXISTS iptv_series_metadata_seq START WITH 1 INCREMENT BY 50;

-- IPTV series metadata cache table
-- Note: response_json uses TEXT type which is unlimited in PostgreSQL (up to ~1GB)
CREATE TABLE iptv_series_metadata
(
    id            BIGINT NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    series_id     VARCHAR(512) NOT NULL,
    response_json TEXT NOT NULL,
    last_accessed TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_iptv_series_metadata PRIMARY KEY (id)
);

-- Indexes for efficient lookups and purging
CREATE INDEX idx_iptv_series_metadata_provider_series ON iptv_series_metadata (provider_name, series_id);
CREATE INDEX idx_iptv_series_metadata_last_accessed ON iptv_series_metadata (last_accessed);

-- Unique constraint on provider_name + series_id
ALTER TABLE iptv_series_metadata
    ADD CONSTRAINT uk_iptv_series_metadata_provider_series UNIQUE (provider_name, series_id);

