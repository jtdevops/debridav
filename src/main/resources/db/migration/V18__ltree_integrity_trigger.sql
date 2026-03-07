CREATE INDEX IF NOT EXISTS idx_db_item_path_gist
ON db_item USING GIST (path);

-- Enforce ltree hierarchy integrity: every directory path must have its parent path existing.
-- Apply this migration only after repairing any existing corruption (missing parent paths).
-- The trigger prevents new corruption by rejecting INSERT/UPDATE that would create orphaned paths.

CREATE OR REPLACE FUNCTION check_ltree_parent_exists()
RETURNS TRIGGER AS $$
BEGIN
  -- Only check directories with non-null path and depth > 1 (ROOT.X has parent ROOT)
  IF NEW.db_item_type = 'DbDirectory' AND NEW.path IS NOT NULL AND nlevel(NEW.path) > 1 THEN
    IF NOT EXISTS (
      SELECT 1 FROM db_item p
      WHERE p.db_item_type = 'DbDirectory'
      AND p.path = subpath(NEW.path, 0, nlevel(NEW.path) - 1)
    ) THEN
      RAISE EXCEPTION 'ltree integrity violation: parent path % does not exist for path %',
        subpath(NEW.path, 0, nlevel(NEW.path) - 1)::text, NEW.path::text;
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ltree_parent_exists_trigger
  BEFORE INSERT OR UPDATE OF path ON db_item
  FOR EACH ROW
  WHEN (NEW.db_item_type = 'DbDirectory' AND NEW.path IS NOT NULL)
  EXECUTE FUNCTION check_ltree_parent_exists();
