CREATE SEQUENCE IF NOT EXISTS iptv_imdb_metadata_seq START WITH 1 INCREMENT BY 50;

-- IPTV IMDB metadata cache table
CREATE TABLE iptv_imdb_metadata
(
    id            BIGINT NOT NULL,
    imdb_id       VARCHAR(32) NOT NULL,
    title         VARCHAR(1024) NOT NULL,
    start_year    INTEGER,
    end_year      INTEGER,
    response_json TEXT NOT NULL,
    last_accessed TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_iptv_imdb_metadata PRIMARY KEY (id)
);

-- Indexes for efficient lookups and purging
CREATE INDEX idx_iptv_imdb_metadata_imdb_id ON iptv_imdb_metadata (imdb_id);
CREATE INDEX idx_iptv_imdb_metadata_last_accessed ON iptv_imdb_metadata (last_accessed);

-- Unique constraint on imdb_id
ALTER TABLE iptv_imdb_metadata
    ADD CONSTRAINT uk_iptv_imdb_metadata_imdb_id UNIQUE (imdb_id);

