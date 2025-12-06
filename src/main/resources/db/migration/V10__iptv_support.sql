CREATE SEQUENCE IF NOT EXISTS iptv_category_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS iptv_content_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS debrid_iptv_content_seq START WITH 1 INCREMENT BY 50;

-- IPTV categories table
CREATE TABLE iptv_category
(
    id            BIGINT NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    category_id   VARCHAR(50) NOT NULL,
    category_name VARCHAR(255) NOT NULL,
    category_type VARCHAR(50) NOT NULL,
    last_synced   TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT pk_iptv_category PRIMARY KEY (id)
);

-- Indexes for categories
CREATE INDEX idx_iptv_category_provider_type ON iptv_category (provider_name, category_type);
CREATE INDEX idx_iptv_category_provider_id ON iptv_category (provider_name, category_id);

-- Unique constraint on provider_name + category_id + category_type
ALTER TABLE iptv_category
    ADD CONSTRAINT uk_iptv_category_provider_id UNIQUE (provider_name, category_id, category_type);

-- IPTV content index table for searchable content
CREATE TABLE iptv_content
(
    id               BIGINT NOT NULL,
    provider_name    VARCHAR(255) NOT NULL,
    content_id       VARCHAR(512) NOT NULL,
    title            VARCHAR(1024) NOT NULL,
    normalized_title VARCHAR(1024) NOT NULL,
    url              VARCHAR(2048) NOT NULL,
    content_type     VARCHAR(50) NOT NULL,
    category_id      BIGINT,
    series_info      JSONB,
    metadata         JSONB,
    last_synced      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    is_active        BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT pk_iptv_content PRIMARY KEY (id)
);

-- Indexes for efficient searching
CREATE INDEX idx_iptv_content_normalized_title ON iptv_content (normalized_title);
CREATE INDEX idx_iptv_content_provider_content_id ON iptv_content (provider_name, content_id);
CREATE INDEX idx_iptv_content_type_active ON iptv_content (content_type, is_active);

-- Unique constraint on provider_name + content_id
ALTER TABLE iptv_content
    ADD CONSTRAINT uk_iptv_content_provider_id UNIQUE (provider_name, content_id);

-- Foreign key constraint for category with ON DELETE CASCADE
-- If a category is deleted, all content items referencing it will be deleted
ALTER TABLE iptv_content
    ADD CONSTRAINT fk_iptv_content_category FOREIGN KEY (category_id) REFERENCES iptv_category(id) ON DELETE CASCADE;

-- IPTV content virtual file table (extends debrid_file_contents)
CREATE TABLE debrid_iptv_content
(
    id                 BIGINT NOT NULL,
    debrid_links       JSONB,
    mime_type          VARCHAR(255),
    modified           BIGINT,
    original_path      VARCHAR(255),
    size               BIGINT,
    iptv_provider_name VARCHAR(255),
    iptv_content_id    VARCHAR(512),
    iptv_content_ref_id BIGINT,
    CONSTRAINT pk_debrid_iptv_content PRIMARY KEY (id)
);

-- Foreign key constraint from debrid_iptv_content to iptv_content with ON DELETE CASCADE
-- If an iptv_content record is deleted, all related debrid_iptv_content records will be deleted
-- Note: iptv_content_ref_id is nullable to allow existing records without the reference
ALTER TABLE debrid_iptv_content
    ADD CONSTRAINT fk_debrid_iptv_content_ref FOREIGN KEY (iptv_content_ref_id) REFERENCES iptv_content(id) ON DELETE CASCADE;

