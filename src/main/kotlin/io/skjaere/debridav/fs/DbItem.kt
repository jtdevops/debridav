package io.skjaere.debridav.fs

import io.ipfs.multibase.Base58
import io.skjaere.debridav.debrid.DebridProvider
import jakarta.persistence.Basic
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorColumn
import jakarta.persistence.DiscriminatorType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Inheritance
import jakarta.persistence.InheritanceType
import jakarta.persistence.JoinColumn
import jakarta.persistence.Lob
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Type


@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "db_item_type", discriminatorType = DiscriminatorType.STRING)
@Table(
    name = "db_item",
    indexes = [Index(name = "directory_path", columnList = "path")],
    uniqueConstraints = [UniqueConstraint(columnNames = arrayOf("directory_id", "name"))]
)
abstract class DbEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var name: String? = null

    open var lastModified: Long? = null

    open var size: Long? = null

    open var mimeType: String? = null

    @ManyToOne(cascade = [CascadeType.MERGE, CascadeType.DETACH], targetEntity = DbDirectory::class)
    open var directory: DbDirectory? = null
}

@Entity
open class DbDirectory : DbEntity() {
    @Column(name = "path", nullable = true, length = Int.MAX_VALUE, unique = true, columnDefinition = "ltree")
    @Type(value = LtreeType::class)
    open var path: String? = null

    fun fileSystemPath(): String? = path
        ?.split(".")
        ?.toMutableList()
        ?.filter { it != "ROOT" }
        ?.joinToString("/") { Base58.decode(it).decodeToString() }
        ?.let { "/$it" }
}

@Entity
open class RemotelyCachedEntity : DbEntity() {
    @OneToOne(cascade = [CascadeType.ALL])
    @JoinColumn(name = "debrid_file_contents_id")
    open var contents: DebridFileContents? = null

    open var hash: String? = null

    fun isNoLongerCached(debridClients: List<DebridProvider>) =
        contents!!
            .debridLinks
            .filter { debridClients.contains(it.provider) }
            .all { it is MissingFile }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RemotelyCachedEntity

        if (contents != other.contents) return false
        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contents?.hashCode() ?: 0
        result = 31 * result + (hash?.hashCode() ?: 0)
        return result
    }


}

@Entity
open class LocalEntity : DbEntity() {
    @OneToOne(fetch = FetchType.LAZY, cascade = [(CascadeType.ALL)])
    @JoinColumn(name = "blob_id")
    open var blob: Blob? = null
}

@Entity
open class Blob() {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    open var size: Long? = null

    @Lob
    @Basic(fetch = FetchType.LAZY)
    open var localContents: java.sql.Blob? = null

    constructor(blob: java.sql.Blob, size: Long) : this() {
        this.localContents = blob
        this.size = size
    }
}

@Entity
open class VirtualStrmDirectory(
    originalPath: String? = null
) : DbDirectory() {
    // Reference to the original directory being mirrored
    open var originalPath: String? = originalPath
        set(value) {
            field = value
            if (value != null) {
                this.path = value.replace("/tv", "/tv_strm").replace("/movies", "/movies_strm")
                this.name = this.path?.substringAfterLast("/")
            }
        }

    init {
        if (originalPath != null) {
            this.path = originalPath.replace("/tv", "/tv_strm").replace("/movies", "/movies_strm")
            this.name = this.path?.substringAfterLast("/")
        }
    }
}

@Entity
open class VirtualStrmFile(
    originalFile: DbEntity? = null,
    originalPath: String? = null
) : DbEntity() {
    // Reference to the original file being mirrored
    open var originalFile: DbEntity? = originalFile
    open var originalPath: String? = originalPath
        set(value) {
            field = value
            val currentOriginalFile = originalFile
            if (value != null && currentOriginalFile != null) {
                this.name = "${currentOriginalFile.name}.strm"
                this.mimeType = "text/plain"
                this.size = value.length.toLong()
            }
        }

    init {
        if (originalFile != null && originalPath != null) {
            this.name = "${originalFile.name}.strm"
            this.mimeType = "text/plain"
            this.size = originalPath.length.toLong()
            this.lastModified = originalFile.lastModified
        }
    }
}
