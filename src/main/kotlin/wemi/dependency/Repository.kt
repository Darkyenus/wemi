package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.util.*
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Path
import java.security.MessageDigest

private val LOG = LoggerFactory.getLogger(Repository::class.java)

/**
 * Describes Maven (version 2 or 3) repository from which [Dependency]-ies are retrieved.
 *
 * Repository layout is described
 * [here](https://cwiki.apache.org/confluence/display/MAVENOLD/Repository+Layout+-+Final)
 * or [here](https://blog.packagecloud.io/eng/2017/03/09/how-does-a-maven-repository-work/).
 */
@Json(Repository.Serializer::class)
class Repository(
        /** Name of this repository, arbitrary (but should be consistent, as it is used for internal bookkeeping) */
        val name: String,
        /** URL of this repository */
        val url: URL,
        /** Repository acting as a cache for this repository, if [local]` == false`, otherwise not used.
         * Must be [local]. Resolved dependencies will be stored here. */
        cache: Repository? = null,
        /** Control checksum to use when retrieving artifacts from here */
        val checksum: Checksum = Checksum.SHA1,
        /** Whether this repository should be used to query for release versions (non-SNAPSHOT) */
        val releases:Boolean = true,
        /** Whether this repository should be used to query for snapshot versions (versions ending with -SNAPSHOT) */
        val snapshots:Boolean = true) {

    /** Local repositories can be caches and may not be cached. Non-local repositories need caches and are not caches.
     * Considered local, if its [url] uses `file:` protocol. */
    val local: Boolean
        get() = "file".equals(url.protocol, ignoreCase = true)

    /** Repository acting as a cache for this repository, if [local]` == false`, otherwise not used.
     * Must be [local]. Resolved dependencies will be stored here. */
    val cache: Repository? =
            if (!local && cache == null) {
                LOG.warn("{} is not local and has no cache, default cache will be used", this)
                LocalCacheM2Repository
            } else if (local && cache != null) {
                LOG.warn("{} is local, but has cache, it will not be used", this)
                null
            } else if (cache != null && !cache.local) {
                LOG.warn("{} is used as a cache for {}, but is not local, so default cache will be used instead", cache, this)
                LocalCacheM2Repository
            } else {
                cache
            }

    init {
        if (!releases && !snapshots) {
            LOG.warn("{} is not used for releases nor snapshots, so it will be always skipped", this)
        }
    }

    /** Path to the directory root, if on this filesystem. Should be [directorySynchronized] to, if writing. */
    internal fun directoryPath(): Path? {
        try {
            if (local) {
                return FileSystems.getDefault().getPath(url.path)
            }
        } catch (ignored:Exception) { }
        return null
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(name).append(" at ").append(url)
        if (cache != null) {
            sb.append(" (cached by ").append(cache.name).append(')')
        }
        return sb.toString()
    }

    internal class Serializer : JsonSerializer<Repository> {
        override fun JsonWriter.write(value: Repository) {
            writeObject {
                field("name", value.name)
                field("url", value.url)

                field("cache", value.cache)
                field("checksum", value.checksum)
                field("releases", value.releases)
                field("snapshots", value.snapshots)
            }
        }

        override fun read(value: JsonValue): Repository {
            return Repository(
                    value.field("name"),
                    value.field("url"),

                    value.field("cache"),
                    value.field("checksum"),
                    value.field("releases"),
                    value.field("snapshots"))
        }
    }
}

/**
 * Types of checksum in Maven repositories.
 *
 * @param suffix of files with this checksum (extension with dot)
 * @param algo Java digest algorithm name to use when computing this checksum
 */
enum class Checksum(val suffix: String, private val algo: String) {
    /**
     * Special value for no checksum.
     *
     * Not recommended for general use - use only in extreme cases.
     */
    None(".no-checksum", "no-op"),
    // https://en.wikipedia.org/wiki/File_verification
    /**
     * Standard SHA1 algorithm with .md5 suffix.
     */
    MD5(".md5", "MD5"),
    /**
     * Standard SHA1 algorithm with .sha1 suffix.
     */
    SHA1(".sha1", "SHA-1");

    /**
     * Creates a [MessageDigest] for this [Checksum].
     * @return null if [None]
     * @throws java.security.NoSuchAlgorithmException if not installed
     */
    fun digest(): MessageDigest? {
        if (this == None) {
            return null
        }
        val digest = MessageDigest.getInstance(algo)
        digest.reset()
        return digest
    }

    fun checksum(data: ByteArray): ByteArray {
        val digest = digest() ?: return ByteArray(0)
        digest.update(data)
        return digest.digest()
    }
}