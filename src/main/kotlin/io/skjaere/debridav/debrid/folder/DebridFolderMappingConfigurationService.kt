package io.skjaere.debridav.debrid.folder

import io.skjaere.debridav.debrid.folder.DebridFolderMappingProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@ConditionalOnProperty(
    prefix = "debridav.debrid-folder-mapping",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class DebridFolderMappingConfigurationService(
    private val folderMappingProperties: DebridFolderMappingProperties,
    private val folderMappingRepository: DebridFolderMappingRepository
) {
    private val logger = LoggerFactory.getLogger(DebridFolderMappingConfigurationService::class.java)

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
        logger.info("Initializing ${configuredMappings.size} debrid folder mappings")

        // Get all existing mappings to check for ones removed from config
        val allExistingMappings = folderMappingRepository.findAll()
        
        // Create a set of configured mapping keys for comparison
        val configuredKeys = configuredMappings.map { mapping ->
            Triple(mapping.provider, mapping.externalPath, mapping.internalPath)
        }.toSet()

        // Process configured mappings (create/update)
        configuredMappings.forEach { mapping ->
            try {
                val existing = folderMappingRepository.findByProviderAndExternalPathAndInternalPath(
                    mapping.provider,
                    mapping.externalPath,
                    mapping.internalPath
                )

                if (existing == null) {
                    val entity = DebridFolderMappingEntity().apply {
                        provider = mapping.provider
                        externalPath = mapping.externalPath
                        internalPath = mapping.internalPath
                        syncMethod = mapping.syncMethod
                        enabled = true
                        syncInterval = folderMappingProperties.syncInterval.toString()
                    }
                    folderMappingRepository.save(entity)
                    logger.info("Created folder mapping: ${mapping.provider}:${mapping.externalPath}=${mapping.internalPath}:${mapping.syncMethod}")
                } else {
                    // Re-enable if it was disabled
                    if (!existing.enabled) {
                        existing.enabled = true
                        logger.info("Re-enabled folder mapping: ${mapping.provider}:${mapping.externalPath}=${mapping.internalPath}")
                    }
                    
                    // Update existing mapping if sync method changed
                    if (existing.syncMethod != mapping.syncMethod) {
                        existing.syncMethod = mapping.syncMethod
                        logger.info("Updated folder mapping sync method: ${mapping.provider}:${mapping.externalPath}")
                    }
                    
                    folderMappingRepository.save(existing)
                }
            } catch (e: Exception) {
                logger.error("Error initializing mapping: ${mapping.provider}:${mapping.externalPath}=${mapping.internalPath}", e)
            }
        }

        // Disable mappings that are no longer in config
        allExistingMappings.forEach { existing ->
            val key = Triple(existing.provider, existing.externalPath, existing.internalPath)
            if (!configuredKeys.contains(key) && existing.enabled) {
                existing.enabled = false
                folderMappingRepository.save(existing)
                logger.info("Disabled mapping no longer in config: ${existing.provider}:${existing.externalPath}=${existing.internalPath}")
            }
        }
    }
}
