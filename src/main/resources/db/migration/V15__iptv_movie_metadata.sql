CREATE SEQUENCE IF NOT EXISTS iptv_movie_metadata_seq START WITH 1 INCREMENT BY 50;

-- IPTV movie metadata cache table
-- Note: response_json uses TEXT type which is unlimited in PostgreSQL (up to ~1GB)
CREATE TABLE iptv_movie_metadata
(
    id            BIGINT NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    movie_id      VARCHAR(512) NOT NULL,
    response_json TEXT NOT NULL,
    last_accessed TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_fetch    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_iptv_movie_metadata PRIMARY KEY (id)
);

-- Indexes for efficient lookups and purging
CREATE INDEX idx_iptv_movie_metadata_provider_movie ON iptv_movie_metadata (provider_name, movie_id);
CREATE INDEX idx_iptv_movie_metadata_last_accessed ON iptv_movie_metadata (last_accessed);
CREATE INDEX idx_iptv_movie_metadata_last_fetch ON iptv_movie_metadata (last_fetch);

-- Unique constraint on provider_name + movie_id
ALTER TABLE iptv_movie_metadata
    ADD CONSTRAINT uk_iptv_movie_metadata_provider_movie UNIQUE (provider_name, movie_id);

