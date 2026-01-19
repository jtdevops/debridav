package io.skjaere.debridav.debrid.folder.api

interface DebridFolderApiClient {
    /**
     * Lists folders in the specified parent path
     * @param parentPath The parent folder path (null for root)
     * @return List of folders
     */
    suspend fun listFolders(parentPath: String?): List<DebridFolder>

    /**
     * Lists files in the specified folder path
     * @param folderPath The folder path to list files from
     * @return List of files in the folder
     */
    suspend fun listFiles(folderPath: String): List<DebridFile>

    /**
     * Gets file information for a specific file
     * @param filePath The full path to the file
     * @param fileId The provider-specific file ID/UUID
     * @return File information or null if file doesn't exist
     */
    suspend fun getFileInfo(filePath: String, fileId: String): DebridFile?

    /**
     * Gets a download/streaming link for a file
     * @param filePath The full path to the file
     * @param fileId The provider-specific file ID/UUID
     * @return Download link or null if unavailable
     */
    suspend fun getDownloadLink(filePath: String, fileId: String): String?
}
