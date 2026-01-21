package io.skjaere.debridav.webdav.folder

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service that manages WebDAV folder mapping configuration on startup.
 * This service always runs (no @ConditionalOnProperty) so it can disable
 * mappings when the feature is disabled.
 */
@Service
class WebDavFolderMappingConfigurationService(
    private val folderMappingProperties: WebDavFolderMappingProperties,
    private val folderMappingRepository: WebDavFolderMappingRepository
) {
    private val logger = LoggerFactory.getLogger(WebDavFolderMappingConfigurationService::class.java)

    @PostConstruct
    @Transactional
    fun initializeMappings() {
        if (!folderMappingProperties.enabled) {
            // Disable all mappings if feature is disabled
            val enabledMappings = folderMappingRepository.findByEnabled(true)
            if (enabledMappings.isNotEmpty()) {
                enabledMappings.forEach { mapping ->
                    mapping.enabled = false
                    folderMappingRepository.save(mapping)
                }
                logger.info("Disabled ${enabledMappings.size} folder mappings because feature is disabled")
            }
            return
        }

        val configuredMappings = folderMappingProperties.getMappingsList()
        val enabledProviders = folderMappingProperties.getProvidersList().map { it.lowercase().trim() }.toSet()
        
        logger.info("Initializing WebDAV folder mappings. Configured mappings: ${configuredMappings.size}, Enabled providers: $enabledProviders")

        // If no providers are enabled, disable all mappings
        if (enabledProviders.isEmpty()) {
            val enabledMappings = folderMappingRepository.findByEnabled(true)
            if (enabledMappings.isNotEmpty()) {
                enabledMappings.forEach { mapping ->
                    mapping.enabled = false
                    folderMappingRepository.save(mapping)
                }
                logger.info("Disabled ${enabledMappings.size} folder mappings because no providers are enabled")
            }
            return
        }

        // Get all existing mappings to check for ones removed from config
        val allExistingMappings = folderMappingRepository.findAll()
        
        // Create a set of configured mapping keys for comparison
        // Only include mappings whose provider is in the enabled providers list
        val configuredKeys = configuredMappings
            .filter { it.providerName.lowercase().trim() in enabledProviders }
            .map { mapping ->
                Triple(mapping.providerName, mapping.externalPath, mapping.internalPath)
            }.toSet()

        // Process configured mappings (create/update) - only for enabled providers
        configuredMappings.forEach { mapping ->
            val providerName = mapping.providerName.lowercase().trim()
            
            // Skip mappings for providers not in the enabled list
            if (providerName !in enabledProviders) {
                logger.info("Skipping mapping for disabled provider: ${mapping.providerName}:${mapping.externalPath}=${mapping.internalPath}")
                return@forEach
            }
            
            try {
                val existing = folderMappingRepository.findByProviderNameAndExternalPathAndInternalPath(
                    mapping.providerName,
                    mapping.externalPath,
                    mapping.internalPath
                )

                if (existing == null) {
                    val entity = WebDavFolderMappingEntity().apply {
                        this.providerName = mapping.providerName
                        externalPath = mapping.externalPath
                        internalPath = mapping.internalPath
                        enabled = true
                        syncInterval = folderMappingProperties.syncInterval.toString()
                    }
                    folderMappingRepository.save(entity)
                    logger.info("Created folder mapping: ${mapping.providerName}:${mapping.externalPath}=${mapping.internalPath}")
                } else {
                    // Re-enable if it was disabled
                    if (!existing.enabled) {
                        existing.enabled = true
                        logger.info("Re-enabled folder mapping: ${mapping.providerName}:${mapping.externalPath}=${mapping.internalPath}")
                    }
                    
                    folderMappingRepository.save(existing)
                }
            } catch (e: Exception) {
                logger.error("Error initializing mapping: ${mapping.providerName}:${mapping.externalPath}=${mapping.internalPath}", e)
            }
        }

        // Disable mappings that are:
        // 1. No longer in config, OR
        // 2. Their provider is not in the enabled providers list
        allExistingMappings.forEach { existing ->
            val key = Triple(existing.providerName, existing.externalPath, existing.internalPath)
            val providerName = (existing.providerName ?: "").lowercase().trim()
            val providerNotEnabled = providerName !in enabledProviders
            val notInConfig = !configuredKeys.contains(key)
            
            if ((providerNotEnabled || notInConfig) && existing.enabled) {
                existing.enabled = false
                folderMappingRepository.save(existing)
                val reason = when {
                    providerNotEnabled -> "provider '$providerName' is not in enabled providers list"
                    else -> "no longer in config"
                }
                logger.info("Disabled mapping ($reason): ${existing.providerName}:${existing.externalPath}=${existing.internalPath}")
            }
        }
    }
}
