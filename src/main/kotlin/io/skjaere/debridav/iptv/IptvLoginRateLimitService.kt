package io.skjaere.debridav.iptv

import io.skjaere.debridav.iptv.configuration.IptvConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Shared rate limiting service for IPTV provider login/verification calls.
 * Tracks rate limits per provider across all services to prevent excessive API calls.
 */
@Service
class IptvLoginRateLimitService(
    private val iptvConfigurationProperties: IptvConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(IptvLoginRateLimitService::class.java)
    
    // Rate limiting for IPTV provider login calls per provider
    // Key: provider name, Value: timestamp of last call
    private val iptvLoginCallTimestamps = ConcurrentHashMap<String, Long>()
    
    // Locks per provider to ensure atomic check-and-record operations
    // This prevents race conditions when multiple threads/coroutines check and record simultaneously
    private val providerLocks = ConcurrentHashMap<String, ReentrantLock>()
    
    private val IPTV_LOGIN_RATE_LIMIT_MS: Long get() = 
        iptvConfigurationProperties.loginRateLimit.toMillis()
    
    /**
     * Atomically checks if a login call should be made and records it if it should.
     * This method is thread-safe and prevents race conditions when multiple threads/coroutines
     * check the rate limit simultaneously. Only one thread will proceed to make the call.
     * 
     * @param providerName The name of the IPTV provider
     * @return true if the call should proceed (enough time has passed), false if rate limited
     */
    fun shouldProceedWithLoginCall(providerName: String): Boolean {
        val lock = providerLocks.computeIfAbsent(providerName) { ReentrantLock() }
        lock.lock()
        try {
            val now = System.currentTimeMillis()
            val lastCallTime = iptvLoginCallTimestamps[providerName] ?: 0L
            val timeSinceLastCall = now - lastCallTime
            
            if (timeSinceLastCall < IPTV_LOGIN_RATE_LIMIT_MS) {
                // Rate limited - don't update timestamp, return false
                return false
            } else {
                // Enough time has passed - update timestamp atomically and return true
                // This ensures only the first thread to check will proceed
                iptvLoginCallTimestamps[providerName] = now
                logger.debug("Atomically recorded login call for IPTV provider: $providerName at timestamp: $now")
                return true
            }
        } finally {
            lock.unlock()
        }
    }
    
    /**
     * Checks if a login call for the given provider should be rate limited.
     * @param providerName The name of the IPTV provider
     * @return true if the call should be rate limited (skip the call), false if the call can proceed
     */
    fun shouldRateLimit(providerName: String): Boolean {
        val now = System.currentTimeMillis()
        val lastCallTime = iptvLoginCallTimestamps[providerName] ?: 0L
        val timeSinceLastCall = now - lastCallTime
        
        return timeSinceLastCall < IPTV_LOGIN_RATE_LIMIT_MS
    }
    
    /**
     * Records that a login call was made for the given provider.
     * Should be called after a successful login call to update the rate limit timestamp.
     * Note: For parallel processing, prefer using shouldProceedWithLoginCall() which atomically checks and records.
     * @param providerName The name of the IPTV provider
     */
    fun recordLoginCall(providerName: String) {
        val now = System.currentTimeMillis()
        iptvLoginCallTimestamps[providerName] = now
        logger.debug("Recorded login call for IPTV provider: $providerName at timestamp: $now")
    }
    
    /**
     * Gets the time since the last login call for the given provider.
     * @param providerName The name of the IPTV provider
     * @return The time in milliseconds since the last call, or Long.MAX_VALUE if no previous call
     */
    fun getTimeSinceLastCall(providerName: String): Long {
        val now = System.currentTimeMillis()
        val lastCallTime = iptvLoginCallTimestamps[providerName] ?: 0L
        return if (lastCallTime == 0L) Long.MAX_VALUE else now - lastCallTime
    }
}

