-- Add db_entity_id column to webdav_synced_file table
-- This links the synced file to its VFS entity (db_item) by ID
-- Allows finding the file even after user renames it

ALTER TABLE webdav_synced_file ADD COLUMN IF NOT EXISTS db_entity_id BIGINT;

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_webdav_synced_file_db_entity_id ON webdav_synced_file(db_entity_id);
