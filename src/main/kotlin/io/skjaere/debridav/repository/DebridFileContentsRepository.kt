package io.skjaere.debridav.repository

import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.fs.DbEntity
import io.skjaere.debridav.fs.DebridIptvContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository

@Suppress("TooManyFunctions")
interface DebridFileContentsRepository : CrudRepository<DbEntity, Long> {
    fun findByDirectoryAndName(directory: DbDirectory, name: String): DbEntity?

    @Query(
        "select * from db_item entity where entity.db_item_type='DbDirectory' AND entity.path = CAST(:path AS ltree)",
        nativeQuery = true
    )
    fun getDirectoryByPath(path: String): DbDirectory?

    fun getByDirectory(directory: DbDirectory): List<DbEntity>

    @Query(
        "select * from db_item directory where directory.path ~ CAST(CONCAT(:#{#directory.path},'.*{1}') AS lquery)",
        nativeQuery = true
    )
    fun getChildrenByDirectory(directory: DbDirectory): List<DbDirectory>

    @Modifying
    @Transactional
    @Query(
        "update db_item set path = CAST(:destinationPath AS ltree) " +
                "|| subpath(path, nlevel(CAST(:#{#directory.path} AS ltree))-1) " +
                "where path <@ CAST(:#{#directory.path} AS ltree)", nativeQuery = true
    )
    fun moveDirectory(directory: DbDirectory, destinationPath: String)

    @Modifying
    @Transactional
    @Query(
        """
            UPDATE db_item 
            set path =
                CASE 
                    WHEN nlevel(path) != nlevel(CAST(:directoryPath as ltree)) THEN subltree(CAST(:directoryPath as ltree), 0, nlevel(CAST(:directoryPath as ltree))-1) || CAST(:encodedNewName AS ltree)  || subpath(path, nlevel(CAST(:directoryPath as ltree)))
                    WHEN nlevel(path) = nlevel(CAST(:directoryPath as ltree)) THEN subltree(CAST(:directoryPath as ltree), 0, nlevel(CAST(:directoryPath as ltree))-1) || CAST(:encodedNewName AS ltree)
                END,
                name = :readableNewName
            where path <@ CAST(:directoryPath as ltree);
            
        """, nativeQuery = true
    )
    fun renameDirectory(directoryPath: String, encodedNewName: String, readableNewName: String)

    @Modifying
    @Transactional
    @Query("delete from torrent_files tf where tf.files_id = :#{#file.id}", nativeQuery = true)
    fun unlinkFileFromTorrents(file: DbEntity)

    @Modifying
    @Transactional
    @Query("delete from usenet_download_debrid_files tf where tf.debrid_files_id = :#{#file.id}", nativeQuery = true)
    fun unlinkFileFromUsenet(file: DbEntity)

    @Modifying
    @Query("delete from RemotelyCachedEntity rce where rce.hash = :hash")
    @Transactional
    fun deleteDbEntityByHash(hash: String)

    @Query("select rce from RemotelyCachedEntity rce where lower(rce.hash) = lower(:hash)")
    fun getByHash(hash: String): List<DbEntity>
    
    @Query("SELECT rce FROM RemotelyCachedEntity rce")
    fun findAllRemotelyCachedEntities(): List<RemotelyCachedEntity>
    
    @Query("""
        select rce from RemotelyCachedEntity rce 
        where rce.contents.id in (
            select dic.id from DebridIptvContent dic 
            where dic.iptvContentRefId = :iptvContentRefId
        )
    """)
    fun findByIptvContentRefId(iptvContentRefId: Long): List<RemotelyCachedEntity>
    
    /**
     * Bulk query to find all VFS files linked to multiple IPTV content IDs in a single query.
     * This avoids N+1 query problems when cleaning up large numbers of streams.
     */
    @Query("""
        select rce from RemotelyCachedEntity rce 
        where rce.contents.id in (
            select dic.id from DebridIptvContent dic 
            where dic.iptvContentRefId in :iptvContentRefIds
        )
    """)
    fun findByIptvContentRefIds(iptvContentRefIds: Collection<Long>): List<RemotelyCachedEntity>

    @Query(
        """
        select  jsonb_path_query(debrid_links, '$[*].provider') as provider, 
                jsonb_path_query(debrid_links, '$[*].\@type') as type, 
                count(*) as count
        from debrid_cached_torrent_content group by provider, type;
    """, nativeQuery = true
    )
    fun getLibraryMetricsTorrents(): List<Map<String, Any>>

    @Query("select count(*) from DebridCachedTorrentContent ")
    fun numberOfRemotelyCachedTorrentEntities(): Long

    @Query("select count(*) from DebridCachedUsenetReleaseContent ")
    fun numberOfRemotelyCachedUsenetEntities(): Long

    @Query(
        """
        SELECT rce.* FROM db_item rce
        INNER JOIN db_item dir ON rce.directory_id = dir.id
        WHERE dir.db_item_type = 'DbDirectory'
        AND dir.path <@ CAST(:downloadPathPrefix AS ltree)
        AND rce.db_item_type = 'RemotelyCachedEntity'
        AND rce.id NOT IN (
            SELECT tf.files_id FROM torrent_files tf
            INNER JOIN torrent t ON tf.torrent_id = t.id
            WHERE t.status = 0
        )
        AND rce.id NOT IN (
            SELECT udf.debrid_files_id FROM usenet_download_debrid_files udf
            INNER JOIN usenet_download ud ON udf.usenet_download_id = ud.id
            WHERE ud.status NOT IN (7, 8)
        )
        AND rce.last_modified < :cutoffTime
        ORDER BY rce.last_modified ASC
        """,
        nativeQuery = true
    )
    fun findAbandonedFilesInDownloads(
        downloadPathPrefix: String,
        cutoffTime: Long
    ): List<RemotelyCachedEntity>

    @Query(
        """
        SELECT rce.* FROM db_item rce
        INNER JOIN db_item dir ON rce.directory_id = dir.id
        WHERE dir.db_item_type = 'DbDirectory'
        AND dir.path <@ CAST(:downloadPathPrefix AS ltree)
        AND rce.db_item_type = 'RemotelyCachedEntity'
        AND rce.id NOT IN (
            SELECT tf.files_id FROM torrent_files tf
            INNER JOIN torrent t ON tf.torrent_id = t.id
            WHERE t.status = 0
        )
        AND rce.id NOT IN (
            SELECT udf.debrid_files_id FROM usenet_download_debrid_files udf
            INNER JOIN usenet_download ud ON udf.usenet_download_id = ud.id
            WHERE ud.status NOT IN (7, 8)
        )
        AND rce.id NOT IN (
            SELECT tf.files_id FROM torrent_files tf
            INNER JOIN torrent t ON tf.torrent_id = t.id
            INNER JOIN category c ON t.category_id = c.id
            WHERE t.status = 0
            AND c.name IN (:arrCategories)
        )
        AND rce.last_modified < :cutoffTime
        ORDER BY rce.last_modified ASC
        """,
        nativeQuery = true
    )
    fun findAbandonedFilesNotLinkedToArrCategories(
        downloadPathPrefix: String,
        cutoffTime: Long,
        arrCategories: List<String>
    ): List<RemotelyCachedEntity>

    @Query(
        """
        SELECT dir.* FROM db_item dir
        WHERE dir.db_item_type = 'DbDirectory'
        AND dir.path <@ CAST(:downloadPathPrefix AS ltree)
        AND dir.path != CAST(:downloadPathPrefix AS ltree)
        AND NOT EXISTS (
            SELECT 1 FROM db_item child
            WHERE child.directory_id = dir.id
        )
        ORDER BY nlevel(dir.path) DESC
        """,
        nativeQuery = true
    )
    fun findEmptyDirectoriesInDownloads(
        downloadPathPrefix: String
    ): List<DbDirectory>
}

data class LibraryStats(val provider: String, val type: String, val count: Long)
