CREATE SEQUENCE IF NOT EXISTS iptv_url_template_seq START WITH 1 INCREMENT BY 50;

-- IPTV URL template table to store base URLs and reduce redundancy
CREATE TABLE iptv_url_template
(
    id            BIGINT NOT NULL,
    provider_name VARCHAR(255) NOT NULL,
    base_url      VARCHAR(2048) NOT NULL,
    content_type  VARCHAR(50),
    last_updated  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_iptv_url_template PRIMARY KEY (id)
);

-- Unique constraint on provider_name + base_url
ALTER TABLE iptv_url_template
    ADD CONSTRAINT uk_iptv_url_template_provider_base UNIQUE (provider_name, base_url);

-- Index for efficient lookups
CREATE INDEX idx_iptv_url_template_provider ON iptv_url_template (provider_name);

-- Add new column to debrid_iptv_content for URL template support
ALTER TABLE debrid_iptv_content
    ADD COLUMN iptv_url_template_id BIGINT;

-- Add foreign key constraint
ALTER TABLE debrid_iptv_content
    ADD CONSTRAINT fk_debrid_iptv_content_url_template 
    FOREIGN KEY (iptv_url_template_id) REFERENCES iptv_url_template(id) ON DELETE SET NULL;

-- Create index for foreign key lookups
CREATE INDEX idx_debrid_iptv_content_url_template ON debrid_iptv_content (iptv_url_template_id);

-- Note: URLs are stored in debrid_links JSONB column (IptvFile.link) with base URL replaced by {IPTV_TEMPLATE_URL}
-- Example: {IPTV_TEMPLATE_URL}/401813119.mkv
-- The base URL is replaced from iptv_url_template.base_url when reading

