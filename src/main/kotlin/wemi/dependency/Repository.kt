package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.WemiException
import wemi.util.*
import java.net.URL
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
        /** Path to local repository acting as a cache for this repository, if [local]` == false`, otherwise not used.
         * Resolved dependencies will be stored here. */
        cache: Path? = null,
        /** Whether this repository should be used to query for release versions (non-SNAPSHOT) */
        val releases:Boolean = true,
        /** Whether this repository should be used to query for snapshot versions (versions ending with -SNAPSHOT) */
        val snapshots:Boolean = true,
        /** When resolving snapshots, check for newer only if cached ones are older than this amount.
         * @see SnapshotCheckDaily default, but other constants are available for convenience */
        val snapshotUpdateDelaySeconds:Long = SnapshotCheckDaily,
        /** Should checksums be downloaded and verified? */
        val verifyChecksums:Boolean = true,
        /** When checksums mismatch, should the resolution fail or warn and continue? (After retrying.) */
        val tolerateChecksumMismatch:Boolean = false,
        /** Local repositories can be caches and may not be cached. Non-local repositories need caches and are not caches.
         * Considered local, if its [url] uses `file:` protocol.
         * WARNING: Making non-local repository local will throw [WemiException].
         * Marking local repository as non-local can be useful, if, for example, the repository is actually on
         * a slow network drive, external drive which may not be present, etc. */
        val local:Boolean = url.isLocal(),
        /** When downloading non-local artifacts, ignore transport safety problems, such as expired https certificates. */
        val useUnsafeTransport:Boolean = false,
        /** Artifacts are first searched for in authoritative repositories and when an artifact is found there,
         * there is no suspicion that it is a [malicious "squatter" artifact](https://blog.autsoft.hu/a-confusing-dependency/).
         * If the artifact is not found in any of authoritative repositories, ALL non-authoritative repositories are searched in,
         * at the same time, to prevent squatter attacks. */
        val authoritative:Boolean = local) {

    /** Same as default constructor, but takes [path] instead of [url]. Useful for local repositories. */
    constructor(name: String, path: Path, cache: Path? = null, releases: Boolean = true, snapshots: Boolean = true,
                snapshotUpdateDelaySeconds: Long = SnapshotCheckDaily, tolerateChecksumMismatch: Boolean = false, local:Boolean = true,
                useUnsafeTransport: Boolean = false, authoritative:Boolean = local)
            : this(name=name, url=path.toUri().toURL(), cache=cache, releases=releases, snapshots=snapshots, snapshotUpdateDelaySeconds=snapshotUpdateDelaySeconds, tolerateChecksumMismatch=tolerateChecksumMismatch, local=local, useUnsafeTransport=useUnsafeTransport, authoritative = authoritative)

    /** Same as default constructor, but takes [url] as a [String] instead of [URL]. */
    constructor(name: String, url: String, cache: Path? = null, releases: Boolean = true, snapshots: Boolean = true,
                snapshotUpdateDelaySeconds: Long = SnapshotCheckDaily, tolerateChecksumMismatch: Boolean = false, local:Boolean = url.startsWith("file:", ignoreCase = true),
                useUnsafeTransport: Boolean = false, authoritative:Boolean = local)
            : this(name=name, url=URL(url), cache=cache, releases=releases, snapshots=snapshots, snapshotUpdateDelaySeconds=snapshotUpdateDelaySeconds, tolerateChecksumMismatch=tolerateChecksumMismatch, local=local, useUnsafeTransport=useUnsafeTransport, authoritative = authoritative)

    fun copy(name:String = this.name, url:URL = this.url, cache:Path? = this.cache,
             releases:Boolean = this.releases, snapshots:Boolean = this.snapshots,
             snapshotUpdateDelaySeconds:Long = this.snapshotUpdateDelaySeconds,
             verifyChecksums: Boolean = this.verifyChecksums,
             tolerateChecksumMismatch: Boolean = this.tolerateChecksumMismatch,
             local:Boolean = this.local,
             useUnsafeTransport: Boolean = this.useUnsafeTransport,
             authoritative:Boolean = this.authoritative):Repository {
        return Repository(name=name, url=url, cache=cache, releases=releases, snapshots=snapshots, snapshotUpdateDelaySeconds=snapshotUpdateDelaySeconds, verifyChecksums = verifyChecksums, tolerateChecksumMismatch=tolerateChecksumMismatch, local=local, useUnsafeTransport=useUnsafeTransport, authoritative = authoritative)
    }

    /** Repository acting as a cache for this repository, if [local]` == false`, otherwise not used.
     * Resolved dependencies will be stored here. */
    val cache: Path? =
            // This logic is relied on by Maven2.retrieveFile
            if (!local && cache == null) {
                LOG.debug("{} is not local and has no cache, default cache will be used", this)
                repositoryCachePath(name)
            } else if (local && cache != null) {
                LOG.warn("{} is local, but has cache specified. It will not be used", this)
                null
            } else {
                cache
            }

    init {
        if (!releases && !snapshots) {
            LOG.warn("{} is not used for releases nor snapshots, so it will be always skipped", this)
        }
        if (local && !url.isLocal()) {
            throw WemiException("Repository with url '$url' cannot be considered local")
        }
        if (local && !authoritative) {
            throw WemiException("A local repository (at '$url') cannot be considered non-authoritative")
        }
    }

    /** Path to the directory root, if on this filesystem. Should be [directorySynchronized] to, if writing. */
    internal fun directoryPath(): Path? = cache ?: url.toPath()

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
                    value.field<URL>("url"),

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

/** [ByteArray] [data] wrapped with (lazily computed) checksums of that data. */
internal class DataWithChecksum(val data:ByteArray) {
    private val checksums = arrayOfNulls<ByteArray>(CHECKSUMS.size)

    fun checksum(type:Checksum):ByteArray {
        return synchronized(checksums) {
            var checksum = checksums[type.ordinal]
            if (checksum == null) {
                checksum = type.checksum(data)
                checksums[type.ordinal] = checksum
            }
            checksum
        }
    }

    fun checksumOrNull(type:Checksum):ByteArray? {
        return checksums[type.ordinal]
    }
}