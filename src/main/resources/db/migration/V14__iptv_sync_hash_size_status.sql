-- Add file size and sync status tracking to IPTV sync hash table
ALTER TABLE iptv_sync_hash
    ADD COLUMN file_size BIGINT,
    ADD COLUMN sync_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN sync_started_at TIMESTAMP WITHOUT TIME ZONE;

-- Update existing rows to have COMPLETED status
UPDATE iptv_sync_hash SET sync_status = 'COMPLETED' WHERE sync_status IS NULL;

