# Database Portability Migration Plan

## Overview

This document outlines the migration plan to make the debridav application database-agnostic by removing PostgreSQL-specific features. The goal is to enable support for other databases like MySQL, MariaDB, SQLite, or H2 while maintaining functionality.

## Current PostgreSQL Dependencies

### 1. **LTREE Extension** (Critical)
- **Used for**: Hierarchical path storage and queries for file system structure
- **Location**: `db_item.path` column
- **Impact**: Used in 4 critical queries for directory operations

### 2. **JSONB Type** (Moderate)
- **Used for**: Storing structured JSON data
- **Locations**: 
  - `debrid_links` in `debrid_cached_torrent_content`, `debrid_cached_usenet_release_content`, `debrid_usenet_contents`, `debrid_iptv_content`
  - `series_info` and `metadata` in `iptv_content`
- **Impact**: One query uses JSONB-specific functions for metrics

### 3. **OID Type** (Optional - Can be removed)
- **Used for**: Large object storage (blob storage)
- **Location**: `blob.local_contents` column
- **Impact**: File chunk caching and local file storage
- **Note**: Can be removed if blob storage feature is not used

---

## Migration Strategy

### Phase 1: Convert JSONB to TEXT + Normalized Metrics Table

#### Step 1.1: Create Normalized Metrics Table

**Migration File**: `V14__create_debrid_link_metrics_table.sql`

```sql
-- Create normalized table for debrid link metrics
CREATE TABLE debrid_link_metrics (
    id BIGSERIAL PRIMARY KEY,
    debrid_file_contents_id BIGINT NOT NULL,
    provider VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL,
    last_updated TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_debrid_link_metrics_contents FOREIGN KEY (debrid_file_contents_id) 
        REFERENCES debrid_cached_torrent_content(id) ON DELETE CASCADE,
    CONSTRAINT uk_debrid_link_metrics_unique UNIQUE (debrid_file_contents_id, provider)
);

-- Create index for efficient metrics queries
CREATE INDEX idx_debrid_link_metrics_provider_type ON debrid_link_metrics (provider, type);
CREATE INDEX idx_debrid_link_metrics_contents_id ON debrid_link_metrics (debrid_file_contents_id);

-- Populate initial data from existing JSONB columns
INSERT INTO debrid_link_metrics (debrid_file_contents_id, provider, type, last_updated)
SELECT 
    id,
    jsonb_extract_path_text(jsonb_array_elements(debrid_links), 'provider')::text as provider,
    jsonb_extract_path_text(jsonb_array_elements(debrid_links), '@type')::text as type,
    NOW()
FROM debrid_cached_torrent_content
WHERE debrid_links IS NOT NULL AND jsonb_array_length(debrid_links) > 0;
```

#### Step 1.2: Convert debrid_links JSONB to TEXT

**Migration File**: `V15__convert_debrid_links_to_text.sql`

```sql
-- Convert debrid_links from JSONB to TEXT in all tables
ALTER TABLE debrid_cached_torrent_content 
    ALTER COLUMN debrid_links TYPE TEXT USING debrid_links::text;

ALTER TABLE debrid_cached_usenet_release_content 
    ALTER COLUMN debrid_links TYPE TEXT USING debrid_links::text;

ALTER TABLE debrid_usenet_contents 
    ALTER COLUMN debrid_links TYPE TEXT USING debrid_links::text;

ALTER TABLE debrid_iptv_content 
    ALTER COLUMN debrid_links TYPE TEXT USING debrid_links::text;
```

#### Step 1.3: Convert IPTV JSONB Columns to TEXT

**Migration File**: `V16__convert_iptv_jsonb_to_text.sql`

```sql
-- Convert series_info and metadata from JSONB to TEXT
ALTER TABLE iptv_content 
    ALTER COLUMN series_info TYPE TEXT USING series_info::text;

ALTER TABLE iptv_content 
    ALTER COLUMN metadata TYPE TEXT USING metadata::text;
```

#### Step 1.4: Code Changes for JSONB Migration

**File**: `src/main/kotlin/io/skjaere/debridav/fs/DebridFileContents.kt`

```kotlin
// Change from:
@Type(JsonBinaryType::class)
@Column(name = "debrid_links", columnDefinition = "jsonb")
open var debridLinks: MutableList<DebridFile> = mutableListOf()

// To:
@Column(name = "debrid_links", columnDefinition = "TEXT")
@Convert(converter = JsonListConverter::class)
open var debridLinks: MutableList<DebridFile> = mutableListOf()
```

**New File**: `src/main/kotlin/io/skjaere/debridav/fs/JsonListConverter.kt`

```kotlin
package io.skjaere.debridav.fs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class JsonListConverter : AttributeConverter<MutableList<DebridFile>, String> {
    private val objectMapper = ObjectMapper()
    
    override fun convertToDatabaseColumn(attribute: MutableList<DebridFile>?): String? {
        return attribute?.let { objectMapper.writeValueAsString(it) }
    }
    
    override fun convertToEntityAttribute(dbData: String?): MutableList<DebridFile> {
        return if (dbData.isNullOrBlank()) {
            mutableListOf()
        } else {
            try {
                objectMapper.readValue<MutableList<DebridFile>>(dbData)
            } catch (e: Exception) {
                mutableListOf()
            }
        }
    }
}
```

**File**: `src/main/kotlin/io/skjaere/debridav/iptv/IptvContentEntity.kt`

```kotlin
// Change from:
@Type(JsonBinaryType::class)
@Column(name = "series_info", columnDefinition = "jsonb")
open var seriesInfo: SeriesInfo? = null

@Type(JsonBinaryType::class)
@Column(name = "metadata", columnDefinition = "jsonb")
open var metadata: Map<String, String> = emptyMap()

// To:
@Column(name = "series_info", columnDefinition = "TEXT")
@Convert(converter = SeriesInfoConverter::class)
open var seriesInfo: SeriesInfo? = null

@Column(name = "metadata", columnDefinition = "TEXT")
@Convert(converter = MetadataConverter::class)
open var metadata: Map<String, String> = emptyMap()
```

**File**: `src/main/kotlin/io/skjaere/debridav/repository/DebridFileContentsRepository.kt`

```kotlin
// Replace JSONB query with normalized table query
@Query(
    """
    SELECT provider, type, COUNT(*) as count
    FROM debrid_link_metrics
    GROUP BY provider, type
    """, nativeQuery = true
)
fun getLibraryMetricsTorrents(): List<Map<String, Any>>
```

**New Service**: `src/main/kotlin/io/skjaere/debridav/debrid/DebridLinkMetricsService.kt`

```kotlin
package io.skjaere.debridav.debrid

import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.repository.DebridLinkMetricsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DebridLinkMetricsService(
    private val metricsRepository: DebridLinkMetricsRepository
) {
    @Transactional
    fun updateMetrics(debridFileContents: DebridFileContents) {
        // Delete existing metrics
        metricsRepository.deleteByDebridFileContentsId(debridFileContents.id!!)
        
        // Insert new metrics
        debridFileContents.debridLinks.forEach { link ->
            metricsRepository.save(
                DebridLinkMetrics(
                    debridFileContentsId = debridFileContents.id!!,
                    provider = link.provider?.name ?: "UNKNOWN",
                    type = link.javaClass.simpleName
                )
            )
        }
    }
}
```

---

### Phase 2: Replace LTREE with VARCHAR + String Operations

#### Step 2.1: Convert LTREE Column to VARCHAR

**Migration File**: `V17__convert_ltree_to_varchar.sql`

```sql
-- Convert path column from LTREE to VARCHAR
ALTER TABLE db_item 
    ALTER COLUMN path TYPE VARCHAR(2048) USING path::text;

-- Drop LTREE extension (optional, but recommended for clean migration)
-- Note: Only drop if no other tables use LTREE
-- DROP EXTENSION IF EXISTS LTREE;
```

#### Step 2.2: Update Entity Annotations

**File**: `src/main/kotlin/io/skjaere/debridav/fs/DbItem.kt`

```kotlin
// Change from:
@Column(name = "path", nullable = true, length = Int.MAX_VALUE, unique = true, columnDefinition = "ltree")
@Type(value = LtreeType::class)
open var path: String? = null

// To:
@Column(name = "path", nullable = true, length = 2048, unique = true)
open var path: String? = null
```

**Note**: Remove `LtreeType.kt` file after migration is complete.

#### Step 2.3: Replace LTREE Queries

**File**: `src/main/kotlin/io/skjaere/debridav/repository/DebridFileContentsRepository.kt`

##### Query 1: Get Directory by Path

```kotlin
// Current:
@Query(
    "select * from db_item entity where entity.db_item_type='DbDirectory' AND entity.path = CAST(:path AS ltree)",
    nativeQuery = true
)
fun getDirectoryByPath(path: String): DbDirectory?

// Replacement (Database-agnostic):
@Query(
    "select * from db_item entity where entity.db_item_type='DbDirectory' AND entity.path = :path",
    nativeQuery = true
)
fun getDirectoryByPath(path: String): DbDirectory?
```

##### Query 2: Get Direct Children

```kotlin
// Current:
@Query(
    "select * from db_item directory where directory.path ~ CAST(CONCAT(:#{#directory.path},'.*{1}') AS lquery)",
    nativeQuery = true
)
fun getChildrenByDirectory(directory: DbDirectory): List<DbDirectory>

// Replacement Option A (PostgreSQL-specific but simpler):
@Query(
    """
    select * from db_item directory 
    where directory.path LIKE CONCAT(:#{#directory.path}, '.%') 
    AND array_length(string_to_array(directory.path, '.'), 1) = 
        array_length(string_to_array(:#{#directory.path}, '.'), 1) + 1
    """,
    nativeQuery = true
)
fun getChildrenByDirectory(directory: DbDirectory): List<DbDirectory>

// Replacement Option B (Database-agnostic - recommended):
// Fetch all descendants and filter in Kotlin
@Query(
    "select * from db_item directory where directory.path LIKE CONCAT(:#{#directory.path}, '%')",
    nativeQuery = true
)
fun getDescendantsByDirectory(directory: DbDirectory): List<DbDirectory>
```

**File**: `src/main/kotlin/io/skjaere/debridav/fs/DatabaseFileService.kt`

Add helper function:

```kotlin
private fun isDirectChild(childPath: String, parentPath: String): Boolean {
    if (!childPath.startsWith(parentPath)) return false
    val remaining = childPath.substring(parentPath.length)
    // Check if remaining is exactly one segment (starts with dot, no more dots)
    return remaining.matches(Regex("^\\.[^.]+$"))
}

// Update getChildrenByDirectory to use helper:
fun getChildrenByDirectory(directory: DbDirectory): List<DbDirectory> {
    val allDescendants = debridFileRepository.getDescendantsByDirectory(directory)
    return allDescendants.filter { 
        it.path != null && isDirectChild(it.path!!, directory.path!!) 
    }
}
```

##### Query 3: Move Directory

```kotlin
// Current:
@Query(
    "update db_item set path = CAST(:destinationPath AS ltree) " +
    "|| subpath(path, nlevel(CAST(:#{#directory.path} AS ltree))-1) " +
    "where path <@ CAST(:#{#directory.path} AS ltree)", 
    nativeQuery = true
)
fun moveDirectory(directory: DbDirectory, destinationPath: String)

// Replacement (PostgreSQL):
@Query(
    """
    UPDATE db_item 
    SET path = CONCAT(:destinationPath, SUBSTRING(path, LENGTH(:#{#directory.path}) + 1))
    WHERE path LIKE CONCAT(:#{#directory.path}, '%')
    """,
    nativeQuery = true
)
fun moveDirectory(directory: DbDirectory, destinationPath: String)

// Alternative (Database-agnostic - recommended):
// Handle in Kotlin service layer
```

**File**: `src/main/kotlin/io/skjaere/debridav/fs/DatabaseFileService.kt`

```kotlin
@Modifying
@Transactional
fun moveDirectory(directory: DbDirectory, destinationPath: String) {
    val oldPath = directory.path!!
    val descendants = debridFileRepository.getDescendantsByDirectory(directory)
    
    descendants.forEach { entity ->
        val relativePath = entity.path!!.substring(oldPath.length)
        entity.path = if (relativePath.isEmpty()) destinationPath else "$destinationPath$relativePath"
        debridFileRepository.save(entity)
    }
    
    // Update the directory itself
    directory.path = destinationPath
    debridFileRepository.save(directory)
}
```

##### Query 4: Rename Directory

```kotlin
// Current: (Complex LTREE manipulation)

// Replacement (Database-agnostic - recommended):
// Handle in Kotlin service layer
```

**File**: `src/main/kotlin/io/skjaere/debridav/fs/DatabaseFileService.kt`

```kotlin
@Modifying
@Transactional
fun renameDirectory(directoryPath: String, encodedNewName: String, readableNewName: String) {
    val pathSegments = directoryPath.split(".")
    if (pathSegments.isEmpty()) return
    
    // Build new parent path (everything except last segment)
    val parentPath = pathSegments.dropLast(1).joinToString(".")
    val newPath = if (parentPath.isEmpty()) encodedNewName else "$parentPath.$encodedNewName"
    
    // Get all descendants
    val descendants = debridFileRepository.getDescendantsByDirectory(
        debridFileRepository.getDirectoryByPath(directoryPath)!!
    )
    
    // Update paths
    descendants.forEach { entity ->
        val relativePath = entity.path!!.substring(directoryPath.length)
        entity.path = if (relativePath.isEmpty()) newPath else "$newPath$relativePath"
        if (entity is DbDirectory && entity.path == directoryPath) {
            entity.name = readableNewName
        }
        debridFileRepository.save(entity)
    }
    
    // Update the directory itself
    val directory = debridFileRepository.getDirectoryByPath(directoryPath)!!
    directory.path = newPath
    directory.name = readableNewName
    debridFileRepository.save(directory)
}
```

---

### Phase 3: Remove OID Dependency (Optional)

#### Step 3.1: Convert OID to BLOB/BINARY

**Migration File**: `V18__convert_oid_to_blob.sql`

```sql
-- Convert OID to BYTEA (PostgreSQL) or BLOB (other databases)
-- Note: This migration is PostgreSQL-specific. For other databases, use appropriate BLOB type

ALTER TABLE blob 
    ALTER COLUMN local_contents TYPE BYTEA USING lo_get(local_contents);

-- Alternative for MySQL/MariaDB:
-- ALTER TABLE blob ALTER COLUMN local_contents TYPE LONGBLOB;

-- Alternative for SQLite:
-- ALTER TABLE blob ALTER COLUMN local_contents TYPE BLOB;
```

#### Step 3.2: Update Code for BLOB Operations

**File**: `src/main/kotlin/io/skjaere/debridav/fs/LocalContentsService.kt`

Replace all `lo_*` function calls with standard JDBC BLOB operations:

```kotlin
// Current PostgreSQL-specific:
val fd = entityManager.createNativeQuery("select lo_open(b.local_contents, $INV_READ::int) as oid from db_item...")

// Replacement (Database-agnostic):
val blob = entity.blob?.localContents
val inputStream = blob?.binaryStream
```

**File**: `src/main/kotlin/io/skjaere/debridav/fs/DatabaseFileService.kt`

```kotlin
// Current:
SELECT lo_unlink(b.loid) from (
    select distinct local_contents as loid from blob b
    ...
)

// Replacement:
// Delete blob records directly - no need for lo_unlink
DELETE FROM blob WHERE id IN (...)
```

---

## Migration Execution Order

1. **Phase 1**: JSONB to TEXT conversion
   - Create metrics table (V14)
   - Convert debrid_links (V15)
   - Convert IPTV columns (V16)
   - Update code to use converters and metrics table

2. **Phase 2**: LTREE to VARCHAR conversion
   - Convert path column (V17)
   - Update entity annotations
   - Replace queries with string-based operations

3. **Phase 3**: OID to BLOB conversion (optional)
   - Convert blob column (V18)
   - Update code to use standard BLOB operations

---

## Testing Checklist

### JSONB Migration
- [ ] Verify debrid_links are stored and retrieved correctly
- [ ] Verify metrics table is populated correctly
- [ ] Verify metrics queries return correct results
- [ ] Test IPTV series_info and metadata storage/retrieval

### LTREE Migration
- [ ] Test directory creation
- [ ] Test directory lookup by path
- [ ] Test getting direct children
- [ ] Test getting all descendants
- [ ] Test directory move operation
- [ ] Test directory rename operation
- [ ] Verify path uniqueness constraints work
- [ ] Test file system path conversion (Base58 encoding/decoding)

### OID Migration (if applicable)
- [ ] Test blob storage and retrieval
- [ ] Test file chunk caching
- [ ] Verify blob deletion works correctly

### Integration Tests
- [ ] Run full test suite
- [ ] Test file system operations end-to-end
- [ ] Test metrics collection
- [ ] Performance testing (compare query performance)

---

## Rollback Plan

### Rollback Phase 1 (JSONB)
```sql
-- Convert back to JSONB
ALTER TABLE debrid_cached_torrent_content 
    ALTER COLUMN debrid_links TYPE JSONB USING debrid_links::jsonb;
-- Repeat for other tables...

-- Drop metrics table
DROP TABLE IF EXISTS debrid_link_metrics;
```

### Rollback Phase 2 (LTREE)
```sql
-- Re-enable LTREE extension
CREATE EXTENSION IF NOT EXISTS LTREE;

-- Convert back to LTREE
ALTER TABLE db_item 
    ALTER COLUMN path TYPE LTREE USING path::ltree;
```

### Rollback Phase 3 (OID)
```sql
-- Convert back to OID (requires data migration)
-- Note: This is complex and may require data export/import
```

---

## Database Compatibility Matrix

| Feature | PostgreSQL | MySQL/MariaDB | SQLite | H2 |
|---------|-----------|---------------|--------|-----|
| TEXT columns | ✅ | ✅ | ✅ | ✅ |
| VARCHAR | ✅ | ✅ | ✅ | ✅ |
| BLOB/BINARY | ✅ (BYTEA) | ✅ (BLOB) | ✅ (BLOB) | ✅ (BLOB) |
| LIKE operator | ✅ | ✅ | ✅ | ✅ |
| String functions | ✅ | ✅ (different syntax) | ✅ (limited) | ✅ |

**Note**: Some string manipulation functions differ between databases. The Kotlin-based approach (Option B) is recommended for maximum portability.

---

## Performance Considerations

### LTREE vs String-based Paths

**LTREE Advantages**:
- Optimized for hierarchical queries
- Built-in indexing support
- Efficient descendant queries

**String-based Advantages**:
- Database portability
- Simpler queries
- Easier to understand and maintain

**Performance Impact**:
- String-based queries may be slightly slower for deep hierarchies
- Consider adding functional indexes: `CREATE INDEX idx_path_prefix ON db_item (path text_pattern_ops);` (PostgreSQL)
- For other databases, consider prefix indexes or full-text search

### JSONB vs TEXT

**JSONB Advantages**:
- Indexed JSON queries
- Efficient JSON operations

**TEXT Advantages**:
- Database portability
- Simpler storage
- Normalized metrics table provides better query performance

**Performance Impact**:
- Metrics queries will be faster with normalized table
- JSON storage/retrieval performance is similar
- Consider adding indexes on metrics table columns

---

## Migration Timeline Estimate

- **Phase 1 (JSONB)**: 2-3 days
  - Migration scripts: 4 hours
  - Code changes: 1 day
  - Testing: 1 day

- **Phase 2 (LTREE)**: 3-4 days
  - Migration scripts: 2 hours
  - Code changes: 2 days
  - Testing: 1-2 days

- **Phase 3 (OID)**: 1-2 days (optional)
  - Migration scripts: 2 hours
  - Code changes: 1 day
  - Testing: 4 hours

**Total**: 6-9 days (or 5-7 days without Phase 3)

---

## Post-Migration Tasks

1. Remove unused dependencies:
   - `io.hypersistence.utils.hibernate.type.json.JsonBinaryType`
   - `LtreeType.kt` class

2. Update documentation:
   - Database setup instructions
   - Supported databases list
   - Migration guide for existing installations

3. Add database-specific configuration:
   - Connection strings for different databases
   - Dialect configuration in `application.properties`

4. Consider adding database abstraction layer:
   - Repository interfaces for database-specific operations
   - Factory pattern for database-specific query builders

---

## Notes

- **Path Format**: The application uses Base58-encoded path segments separated by dots (e.g., `ROOT.encoded1.encoded2`). This format is preserved in the migration.

- **Backward Compatibility**: The migration maintains data compatibility - existing data will be converted automatically.

- **Zero Downtime**: Consider running migrations during maintenance windows or use blue-green deployment strategy.

- **Monitoring**: Add monitoring for query performance after migration to identify any issues.

---

## Questions or Issues?

If you encounter issues during migration:
1. Check database logs for errors
2. Verify data integrity after each phase
3. Test rollback procedures in staging environment
4. Consult database-specific documentation for function differences

