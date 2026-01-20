-- WebDAV Folder Mapping Tables
-- Creates tables to track folder mappings and synced files from WebDAV providers

CREATE TABLE webdav_folder_mapping (
    id BIGSERIAL PRIMARY KEY,
    provider_name VARCHAR(100) NOT NULL,
    external_path VARCHAR(2048) NOT NULL,
    internal_path VARCHAR(2048) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    last_synced TIMESTAMP,
    sync_interval VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_mapping UNIQUE (provider_name, external_path, internal_path)
);

CREATE INDEX idx_webdav_folder_mapping_provider_name ON webdav_folder_mapping(provider_name);
CREATE INDEX idx_webdav_folder_mapping_enabled ON webdav_folder_mapping(enabled);
CREATE INDEX idx_webdav_folder_mapping_internal_path ON webdav_folder_mapping(internal_path);

CREATE TABLE webdav_synced_file (
    id BIGSERIAL PRIMARY KEY,
    folder_mapping_id BIGINT NOT NULL REFERENCES webdav_folder_mapping(id) ON DELETE CASCADE,
    provider_file_id VARCHAR(512) NOT NULL,
    provider_file_path VARCHAR(2048) NOT NULL,
    vfs_path VARCHAR(2048) NOT NULL,
    vfs_file_name VARCHAR(512),
    file_size BIGINT,
    mime_type VARCHAR(255),
    provider_link VARCHAR(2048),
    last_checked TIMESTAMP,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_synced_file UNIQUE (folder_mapping_id, provider_file_id)
);

CREATE INDEX idx_webdav_synced_file_folder_mapping_id ON webdav_synced_file(folder_mapping_id);
CREATE INDEX idx_webdav_synced_file_provider_file_id ON webdav_synced_file(provider_file_id);
CREATE INDEX idx_webdav_synced_file_vfs_path ON webdav_synced_file(vfs_path);
CREATE INDEX idx_webdav_synced_file_is_deleted ON webdav_synced_file(is_deleted);
