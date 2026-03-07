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
        """
        select *
        from db_item d
        where d.path <@ CAST(:#{#directory.path} AS ltree)
        and nlevel(d.path) = nlevel(CAST(:#{#directory.path} AS ltree)) + 1
        """,
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
        AND NOT EXISTS (
            SELECT 1 FROM db_item subdir
            WHERE subdir.db_item_type = 'DbDirectory'
            AND subdir.path <@ dir.path
            AND subdir.path != dir.path
        )
        ORDER BY nlevel(dir.path) DESC
        """,
        nativeQuery = true
    )
    fun findEmptyDirectoriesInDownloads(
        downloadPathPrefix: String
    ): List<DbDirectory>

    /**
     * Finds directories whose parent path does not exist in the table (broken ltree hierarchy).
     * These should be deleted to restore tree integrity.
     * Ordered by depth ascending (shallowest first) so deleting a parent cascades to children.
     */
    @Query(
        """
        SELECT d.* FROM db_item d
        WHERE d.db_item_type = 'DbDirectory'
        AND d.path IS NOT NULL
        AND nlevel(d.path) > 1
        AND d.path <@ CAST(:downloadPathPrefix AS ltree)
        AND NOT EXISTS (
            SELECT 1 FROM db_item p
            WHERE p.db_item_type = 'DbDirectory'
            AND p.path = subpath(d.path, 0, nlevel(d.path) - 1)
        )
        ORDER BY nlevel(d.path) ASC
        """,
        nativeQuery = true
    )
    fun findDirectoriesWithMissingParentPath(downloadPathPrefix: String): List<DbDirectory>

    @Query(
        """
        SELECT dir.* FROM db_item dir
        WHERE dir.db_item_type = 'DbDirectory'
        AND dir.path <@ CAST(:pathPrefix AS ltree)
        AND dir.path != CAST(:pathPrefix AS ltree)
        ORDER BY nlevel(dir.path) DESC
        """,
        nativeQuery = true
    )
    fun findAllDirectoriesUnderPath(pathPrefix: String): List<DbDirectory>

    @Query(
        """
        SELECT child.* FROM db_item child
        WHERE child.directory_id = :directoryId
        AND child.db_item_type != 'DbDirectory'
        """,
        nativeQuery = true
    )
    fun findFilesInDirectory(directoryId: Long): List<DbEntity>

    @Modifying
    @Transactional
    @Query(
        """
        DELETE FROM db_item
        WHERE db_item_type = 'DbDirectory'
        AND path <@ CAST(:directoryPath AS ltree)
        AND path != CAST(:directoryPath AS ltree)
        """,
        nativeQuery = true
    )
    fun deleteChildDirectoriesByPath(directoryPath: String): Int

    @Query(
        """
        SELECT child.* FROM db_item child
        INNER JOIN db_item dir ON child.directory_id = dir.id
        WHERE dir.db_item_type = 'DbDirectory'
        AND dir.path <@ CAST(:directoryPath AS ltree)
        AND child.db_item_type != 'DbDirectory'
        """,
        nativeQuery = true
    )
    fun findFilesInDirectoryTree(directoryPath: String): List<DbEntity>
}

data class LibraryStats(val provider: String, val type: String, val count: Long)
