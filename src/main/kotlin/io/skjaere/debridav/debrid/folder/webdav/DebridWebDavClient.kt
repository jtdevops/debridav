package io.skjaere.debridav.debrid.folder.webdav

interface DebridWebDavClient {
    /**
     * Lists files in the specified folder path
     * @param folderPath The folder path to list (e.g., "/Downloads/TV")
     * @return List of files and subdirectories in the folder
     */
    suspend fun listFiles(folderPath: String): List<WebDavFile>

    /**
     * Gets file information for a specific file
     * @param filePath The full path to the file
     * @return File information or null if file doesn't exist
     */
    suspend fun getFileInfo(filePath: String): WebDavFile?

    /**
     * Gets a download/streaming link for a file
     * @param filePath The full path to the file
     * @return Download link or null if unavailable
     */
    suspend fun getDownloadLink(filePath: String): String?
}
