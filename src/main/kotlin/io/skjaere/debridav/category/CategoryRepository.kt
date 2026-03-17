package io.skjaere.debridav.category

import org.springframework.data.repository.CrudRepository

interface CategoryRepository : CrudRepository<Category, Long> {
    fun findByNameIgnoreCase(name: String): Category?
    fun findByDownloadPath(downloadPath: String): List<Category>

    /**
     * Returns categories that use the downloads folder for cleanup purposes.
     * Includes categories with matching downloadPath OR null downloadPath (legacy categories
     * created before downloadPath was set) to avoid incorrectly treating their torrents as orphaned.
     */
    fun findByDownloadPathOrDownloadPathIsNull(downloadPath: String): List<Category>
}
