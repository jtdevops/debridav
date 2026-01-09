package io.skjaere.debridav.torrent

import io.skjaere.debridav.category.Category
import io.skjaere.debridav.fs.DbEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TorrentRepository : CrudRepository<Torrent, Long> {
    fun findByCategoryAndStatus(category: Category, status: Status): List<Torrent>
    fun getByHashIgnoreCase(hash: String): Torrent?
    fun findByHashIgnoreCase(hash: String): List<Torrent>

    fun deleteByHashIgnoreCase(hash: String)

    @Modifying
    @Query("update Torrent set status=io.skjaere.debridav.torrent.Status.DELETED where id=:#{#torrent.id}")
    fun markTorrentAsDeleted(torrent: Torrent)

    fun getTorrentByFilesContains(file: DbEntity): List<Torrent>
    
    /**
     * Finds all LIVE torrents with their category loaded.
     * Uses JOIN FETCH to avoid N+1 queries when accessing category.
     */
    @Query("SELECT DISTINCT t FROM Torrent t LEFT JOIN FETCH t.category WHERE t.status = :status")
    fun findAllByStatusWithCategory(status: Status): List<Torrent>
    
    /**
     * Finds LIVE torrents in the downloads folder with their category loaded.
     * Filters by status and savePath prefix in the database to avoid loading unnecessary data.
     */
    @Query("SELECT DISTINCT t FROM Torrent t LEFT JOIN FETCH t.category WHERE t.status = :status AND t.savePath LIKE :savePathPrefix%")
    fun findAllByStatusAndSavePathPrefixWithCategory(status: Status, savePathPrefix: String): List<Torrent>
}
