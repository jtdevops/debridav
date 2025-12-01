-- Add lastFetch column to track when metadata was last fetched from API
-- This migration is for existing databases that were created before last_fetch was added to V12

-- Check if column doesn't exist before adding (for idempotency)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'iptv_series_metadata' 
        AND column_name = 'last_fetch'
    ) THEN
        -- Add column as nullable first
        ALTER TABLE iptv_series_metadata
            ADD COLUMN last_fetch TIMESTAMP WITHOUT TIME ZONE;

        -- Set last_fetch to created_at for existing records (or current timestamp if created_at is null)
        UPDATE iptv_series_metadata
        SET last_fetch = COALESCE(created_at, CURRENT_TIMESTAMP)
        WHERE last_fetch IS NULL;

        -- Make last_fetch NOT NULL after setting values
        ALTER TABLE iptv_series_metadata
            ALTER COLUMN last_fetch SET NOT NULL;
    END IF;
END $$;

-- Create index for efficient lookups based on last_fetch (if it doesn't exist)
CREATE INDEX IF NOT EXISTS idx_iptv_series_metadata_last_fetch ON iptv_series_metadata (last_fetch);
