# ltree Hierarchy Integrity Investigation

## Executive Summary

The corruption (paths like `ROOT.2H9aS71LC814W.ABC.DEF` existing without parent `ROOT.2H9aS71LC814W.ABC`) was caused by **directory deletion logic that does not cascade to path-based descendants**. Subdirectories are linked to parents only via the `path` column (ltree), not via `directory_id`. Several code paths delete directories without first removing their path-descendants.

---

## Root Cause Analysis

### Key Architectural Facts

1. **Dual hierarchy model**: 
   - **Files** use `directory_id` → parent folder
   - **Directories** use `path` (ltree) for hierarchy; most have `directory_id = NULL`
   
2. **`findEmptyDirectoriesInDownloads`** checks `NOT EXISTS (child WHERE child.directory_id = dir.id)` — it only considers `directory_id` children. Subdirectories are never counted because they don't set `directory_id`.

3. **`databaseFileService.deleteFile(DbDirectory)`** performs a single-row delete with no cascade to path-descendants.

---

## Bug Patterns Identified

### 1. **DirectoryResource.delete()** (WebDAV) — LIKELY CULPRIT

**Location**: `DirectoryResource.kt:72-74`

```kotlin
override fun delete() {
    fileService.deleteFile(directory)
}
```

**Issue**: Deletes only the directory row. Milton WebDAV typically recurses and deletes children first, but:
- If the client sends a single DELETE on a collection without depth-first behavior
- If there is any framework bug or race
- The code has no defensive check for path-descendants

**Result**: A directory with subdirectories (but no files) could be deleted, orphaning those subdirectories.

---

### 2. **DownloadsCleanupService** — POTENTIAL (mitigated by ordering)

**Location**: `DownloadsCleanupService.kt` — uses `findEmptyDirectoriesInDownloads` and `deleteFile(directory)`

**Issue**: `findEmptyDirectoriesInDownloads` considers a directory "empty" if it has no `directory_id` children. A directory containing **only subdirectories** (no files) is treated as empty.

**Mitigation**: Processing order is `ORDER BY nlevel(dir.path) DESC` (deepest first), so children are deleted before parents. This ordering prevents corruption in normal operation.

**Risk**: If the query or iteration order ever changes, or in edge cases (e.g., concurrent modifications), parent could be deleted before children.

---

### 3. **databaseFileService.deleteFile(DbDirectory)** — ROOT CAUSE

**Location**: `DatabaseFileService.kt:266-269`

```kotlin
is DbDirectory -> debridFileRepository.delete(file)
```

**Issue**: Single-row delete with no cascade. Any caller that deletes a directory without first removing path-descendants can cause corruption.

---

### 4. **WebDavFolderSyncService.cleanupDirectories** — CORRECT

**Location**: `WebDavFolderSyncService.kt:594-617`

This code correctly:
1. Deletes files in the tree
2. Calls `deleteChildDirectoriesByPath(dirPath)` to remove all path-descendants
3. Then deletes the directory itself

---

## Code Paths That Write to db_item

| Operation | Location | Hierarchy-Safe? |
|-----------|----------|-----------------|
| Directory creation | `DatabaseFileService.getOrCreateDirectory` | ✅ Creates parents first via `getDirectoryTreePaths` |
| Directory rename | `DebridFileContentsRepository.renameDirectory` | ✅ Updates all descendants in single query |
| Directory move | `DebridFileContentsRepository.moveDirectory` | ✅ Updates all descendants in single query |
| Directory delete (generic) | `DatabaseFileService.deleteFile` | ❌ No cascade |
| Directory delete (WebDAV sync) | `WebDavFolderSyncService.cleanupDirectories` | ✅ Cascades via `deleteChildDirectoriesByPath` |
| Empty dir cleanup | `DownloadsCleanupService` | ⚠️ Relies on depth-first ordering |
| WebDAV user delete | `DirectoryResource.delete` | ⚠️ Relies on Milton recursion |

---

## Recommended Fixes

### Fix 1: Centralize Safe Directory Deletion

Add a `deleteDirectory` method that always cascades to path-descendants:

```kotlin
@Transactional
fun deleteDirectory(directory: DbDirectory) {
    val path = directory.path ?: return
    // 1. Delete files in tree
    debridFileRepository.findFilesInDirectoryTree(path).forEach { deleteFile(it) }
    // 2. Delete child directories (path-descendants)
    debridFileRepository.deleteChildDirectoriesByPath(path)
    // 3. Delete the directory itself
    debridFileRepository.delete(directory)
}
```

Then update `deleteFile(DbDirectory)` to call this.

### Fix 2: Harden findEmptyDirectoriesInDownloads

Exclude directories that have path-based descendants:

```sql
AND NOT EXISTS (
    SELECT 1 FROM db_item child
    WHERE child.db_item_type = 'DbDirectory'
    AND child.path <@ dir.path
    AND child.path != dir.path
)
```

This makes the "empty" check robust even if processing order changes.

### Fix 3: Database Trigger (Optional)

A trigger can enforce that no directory exists whose parent path is missing:

```sql
CREATE OR REPLACE FUNCTION check_ltree_parent_exists()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.path IS NOT NULL AND nlevel(NEW.path) > 1 THEN
    IF NOT EXISTS (
      SELECT 1 FROM db_item
      WHERE db_item_type = 'DbDirectory'
      AND path = subpath(NEW.path, 0, nlevel(NEW.path) - 1)
    ) THEN
      RAISE EXCEPTION 'ltree integrity: parent path % does not exist', subpath(NEW.path, 0, nlevel(NEW.path) - 1);
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ltree_parent_check
  BEFORE INSERT OR UPDATE OF path ON db_item
  FOR EACH ROW EXECUTE FUNCTION check_ltree_parent_exists();
```

### Fix 4: Pre-Delete Validation

Before any directory delete, assert no path-descendants exist (defensive programming).

---

## Diagnostic Query (for future use)

To detect existing corruption:

```sql
SELECT d.path, subpath(d.path, 0, nlevel(d.path) - 1) AS parent_path
FROM db_item d
WHERE d.db_item_type = 'DbDirectory'
AND d.path IS NOT NULL
AND nlevel(d.path) > 1
AND NOT EXISTS (
  SELECT 1 FROM db_item p
  WHERE p.db_item_type = 'DbDirectory'
  AND p.path = subpath(d.path, 0, nlevel(d.path) - 1)
);
```
