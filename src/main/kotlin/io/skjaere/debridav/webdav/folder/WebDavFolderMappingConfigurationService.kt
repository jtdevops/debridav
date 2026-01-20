package io.skjaere.debridav.webdav.folder

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@ConditionalOnProperty(
    prefix = "debridav.webdav-folder-mapping",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
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
        logger.info("Initializing ${configuredMappings.size} WebDAV folder mappings")

        // Get all existing mappings to check for ones removed from config
        val allExistingMappings = folderMappingRepository.findAll()
        
        // Create a set of configured mapping keys for comparison
        val configuredKeys = configuredMappings.map { mapping ->
            Triple(mapping.providerName, mapping.externalPath, mapping.internalPath)
        }.toSet()

        // Process configured mappings (create/update)
        configuredMappings.forEach { mapping ->
            try {
                val existing = folderMappingRepository.findByProviderNameAndExternalPathAndInternalPath(
                    mapping.providerName,
                    mapping.externalPath,
                    mapping.internalPath
                )

                if (existing == null) {
                    val entity = WebDavFolderMappingEntity().apply {
                        providerName = mapping.providerName
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

        // Disable mappings that are no longer in config
        allExistingMappings.forEach { existing ->
            val key = Triple(existing.providerName, existing.externalPath, existing.internalPath)
            if (!configuredKeys.contains(key) && existing.enabled) {
                existing.enabled = false
                folderMappingRepository.save(existing)
                logger.info("Disabled mapping no longer in config: ${existing.providerName}:${existing.externalPath}=${existing.internalPath}")
            }
        }
    }
}
