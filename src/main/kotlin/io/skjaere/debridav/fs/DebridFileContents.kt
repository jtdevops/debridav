package io.skjaere.debridav.fs

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import io.skjaere.debridav.debrid.DebridProvider
import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorColumn
import jakarta.persistence.DiscriminatorType
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Inheritance
import jakarta.persistence.InheritanceType
import org.hibernate.annotations.Type
import java.io.Serializable

@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@DiscriminatorColumn(name = "file_type", discriminatorType = DiscriminatorType.STRING)
abstract class DebridFileContents {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null
    open var originalPath: String? = null
    open var size: Long? = null
    open var modified: Long? = null
    open var mimeType: String? = null

    @Type(JsonBinaryType::class)
    @Column(name = "debrid_links", columnDefinition = "jsonb")
    open var debridLinks: MutableList<DebridFile> = mutableListOf()

    fun replaceOrAddDebridLink(debridLink: DebridFile) {
        if (debridLinks.any { link -> link.provider == debridLink.provider }) {
            val index = debridLinks.indexOfFirst { link -> link.provider == debridLink.provider }
            debridLinks[index] = debridLink
        } else {
            debridLinks.add(debridLink)
        }
    }
}

@Entity
open class DebridCachedTorrentContent() : DebridFileContents() {
    @Column(name = "magnet", length = 2048)
    open var magnet: String? = null

    constructor(magnet: String) : this() {
        this.magnet = magnet
    }

    constructor(
        originalPath: String?,
        size: Long?,
        modified: Long?,
        magnet: String?,
        mimeType: String?,
        debridLinks: MutableList<DebridFile>
    ) : this() {
        this.originalPath = originalPath
        this.size = size
        this.modified = modified
        this.magnet = magnet
        this.debridLinks = debridLinks
        this.mimeType = mimeType
    }
}

@Entity
open class DebridCachedUsenetReleaseContent() : DebridFileContents() {
    @Column(name = "releaseName", length = 2048)
    open var releaseName: String? = null

    constructor(releaseName: String) : this() {
        this.releaseName = releaseName
    }

    constructor(
        originalPath: String?,
        size: Long?,
        modified: Long?,
        releaseName: String?,
        mimeType: String?,
        debridLinks: MutableList<DebridFile>
    ) : this() {
        this.originalPath = originalPath
        this.size = size
        this.modified = modified
        this.releaseName = releaseName
        this.debridLinks = debridLinks
        this.mimeType = mimeType
    }
}

@Entity
open class DebridUsenetContents : DebridFileContents() {
    open var usenetDownloadId: Long? = null
    open var nzbFileLocation: String? = null
    open var hash: String? = null
    //open var mimeType: String? = null
}

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY
)
@JsonSubTypes(
    value = [
        JsonSubTypes.Type(CachedFile::class, name = "CachedFile"),
        JsonSubTypes.Type(MissingFile::class, name = "MissingFile"),
        JsonSubTypes.Type(ProviderError::class, name = "ProviderError"),
        JsonSubTypes.Type(ClientError::class, name = "ClientError"),
        JsonSubTypes.Type(NetworkError::class, name = "NetworkError"),
        JsonSubTypes.Type(UnknownError::class, name = "UnknownError"),
    ]
)

@Suppress("SerialVersionUIDInSerializableClass")
abstract class DebridFile : Serializable {
    open var provider: DebridProvider? = null
    open var lastChecked: Long? = null
}


@JsonTypeName("CachedFile")
open class CachedFile() : DebridFile() {
    //override var type: String? = "CachedFile"
    @JsonProperty("@type")
    open var type: String = "CachedFile"
    open var path: String? = null
    open var size: Long? = null
    open var mimeType: String? = null
    open var link: String? = null
    open var params: Map<String, String>? = mutableMapOf()

    @Suppress("LongParameterList")
    constructor(
        path: String,
        size: Long,
        mimeType: String,
        link: String,
        params: Map<String, String>?,
        lastChecked: Long,
        provider: DebridProvider
    ) : this() {
        this.path = path
        this.size = size
        this.mimeType = mimeType
        this.link = link
        this.params = params
        this.provider = provider
        this.lastChecked = lastChecked
    }
}

@JsonTypeName("MissingFile")
open class MissingFile() : DebridFile() {
    @JsonProperty("@type")
    open var type: String = "MissingFile"

    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@JsonTypeName("ProviderError")
open class ProviderError() : DebridFile() {
    @JsonProperty("@type")
    open var type: String = "ProviderError"

    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@JsonTypeName("ClientError")
open class ClientError() : DebridFile() {
    @JsonProperty("@type")
    open var type: String = "ClientError"

    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@JsonTypeName("NetworkError")
open class NetworkError() : DebridFile() {
    @JsonProperty("@type")
    open var type: String = "NetworkError"

    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}

@JsonTypeName("UnknownError")
open class UnknownError() : DebridFile() {
    @JsonProperty("@type")
    open var type: String = "UnknownError"

    constructor(debridProvider: DebridProvider, lastChecked: Long) : this() {
        this.provider = debridProvider
        this.lastChecked = lastChecked
    }
}
