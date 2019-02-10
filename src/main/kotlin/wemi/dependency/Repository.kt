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

/** @see Repository.snapshotUpdateDelaySeconds */
const val SnapshotCheckAlways = 0L
const val SnapshotCheckHourly = 60L * 60L
const val SnapshotCheckDaily = SnapshotCheckHourly * 24L
const val SnapshotCheckNever = Long.MAX_VALUE

/**
 * Describes Maven (version 2 or 3) repository from which [Dependency]-ies are retrieved.
 *
 * Repository layout is described
 * [here](https://cwiki.apache.org/confluence/display/MAVENOLD/Repository+Layout+-+Final)
 * or [here](https://blog.packagecloud.io/eng/2017/03/09/how-does-a-maven-repository-work/).
 */
@Json(Repository.Serializer::class)
class Repository(
        /** Name of this repository, arbitrary (but should be consistent and valid filename, as it is used for internal bookkeeping) */
        val name: String,
        /** URL of this repository */
        val url: URL,
        /** Repository acting as a cache for this repository, if [local]` == false`, otherwise not used.
         * Must be [local]. Resolved dependencies will be stored here. */
        cache: Repository? = null,
        /** Whether this repository should be used to query for release versions (non-SNAPSHOT) */
        val releases:Boolean = true,
        /** Whether this repository should be used to query for snapshot versions (versions ending with -SNAPSHOT) */
        val snapshots:Boolean = true,
        /** When resolving snapshots, check for newer only if cached ones are older than this amount.
         * @see SnapshotCheckDaily default, but other constants are available for convenience */
        val snapshotUpdateDelaySeconds:Long = SnapshotCheckDaily,
        /** When checksums mismatch, should the resolution fail or warn and continue? (After retrying.) */
        val tolerateChecksumMismatch:Boolean = false) {

    /** Local repositories can be caches and may not be cached. Non-local repositories need caches and are not caches.
     * Considered local, if its [url] uses `file:` protocol. */
    val local: Boolean
        get() = "file".equals(url.protocol, ignoreCase = true)

    /** Repository acting as a cache for this repository, if [local]` == false`, otherwise not used.
     * Must be [local]. Resolved dependencies will be stored here. */
    val cache: Repository? =
            // This logic is relied on by Maven2.retrieveFile
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
                field("releases", value.releases)
                field("snapshots", value.snapshots)
            }
        }

        override fun read(value: JsonValue): Repository {
            return Repository(
                    value.field("name"),
                    value.field("url"),

                    value.field("cache"),
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
     * @throws java.security.NoSuchAlgorithmException if not installed
     */
    fun digest(): MessageDigest {
        val digest = MessageDigest.getInstance(algo)
        digest.reset()
        return digest
    }

    fun checksum(data: ByteArray): ByteArray {
        val digest = digest()
        digest.update(data)
        return digest.digest()
    }
}

internal val CHECKSUMS = Checksum.values()