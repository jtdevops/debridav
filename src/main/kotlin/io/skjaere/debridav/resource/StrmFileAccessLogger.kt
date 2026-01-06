package io.skjaere.debridav.resource

import org.slf4j.LoggerFactory

/**
 * Dedicated logger for STRM file access events.
 * This logger can be configured independently to enable TRACE logging for STRM file access
 * while keeping the rest of the application at INFO level.
 * 
 * Configuration:
 * - application.properties: logging.level.io.skjaere.debridav.resource.StrmFileAccessLogger=TRACE
 * - Docker env: LOGGING_LEVEL_IO_SKJAERE_DEBRIDAV_RESOURCE_STRMFILEACCESSLOGGER=TRACE
 */
object StrmFileAccessLogger {
    private val logger = LoggerFactory.getLogger(StrmFileAccessLogger::class.java)
    
    fun logContentComputed(file: String, provider: String?, contentType: String, content: String) {
        logger.trace("STRM_CONTENT_COMPUTED: file={}, provider={}, contentType={}, content={}", 
            file, provider ?: "null", contentType, content)
    }
    
    fun logContentSent(file: String, range: String?, contentLength: Int) {
        logger.trace("STRM_CONTENT_SENT: file={}, range={}, contentLength={}", 
            file, range ?: "full", contentLength)
    }
    
    fun logContentLengthQueried(file: String, length: Long) {
        logger.trace("STRM_CONTENT_LENGTH_QUERIED: file={}, length={}", file, length)
    }
}

