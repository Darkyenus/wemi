package wemi.dependency

import com.esotericsoftware.jsonbeans.Json
import org.slf4j.LoggerFactory
import wemi.boot.MachineWritable
import wemi.collections.WSet
import wemi.collections.wSetOf
import wemi.publish.InfoNode
import wemi.util.*
import java.net.URI
import java.net.URL
import java.nio.file.*
import java.security.MessageDigest

/**
 * Represents repository from which artifacts may be retrieved
 */
sealed class Repository(val name: String) : MachineWritable {

    /** Local repositories are preferred, because retrieving from them is faster. */
    abstract val local: Boolean

    /** Repository acting as a cache for this repository. Always searched first.
     * Resolved dependencies will be stored here. */
    abstract val cache: Repository?

    /**
     * Attempt to resolve given dependency in this repository.
     *
     * @param dependency to resolve
     * @param chain of repositories which may be queried for looking up dependencies
     */
    internal abstract fun resolveInRepository(dependency: Dependency, chain: RepositoryChain): ResolvedDependency

    /**
     * @return directory to lock on while resolving from this repository
     */
    internal abstract fun directoryToLock(): Path?

    /**
     * Publish [artifacts]s to this repository, under given [metadata]
     * and return the general location to where it was published.
     *
     * @param artifacts list of artifacts and their classifiers if any
     */
    internal abstract fun publish(metadata: InfoNode, artifacts: List<Pair<Path, String?>>):URI

    /** Maven repository.
     *
     * @param name of this repository, arbitrary
     * @param url of this repository
     * @param cache of this repository
     * @param checksum to use when retrieving artifacts from here
     */
    class M2(name: String, val url: URL, override val cache: M2? = null, val checksum: Checksum = M2.Checksum.SHA1) : Repository(name) {

        override val local: Boolean
            get() = "file".equals(url.protocol, ignoreCase = true)

        override fun resolveInRepository(dependency: Dependency, chain: RepositoryChain): ResolvedDependency {
            return Maven2.resolveInM2Repository(dependency, this, chain)
        }

        override fun directoryToLock(): Path? {
            return directoryPath()
        }

        /**
         * @return Path to the directory root, if on this filesystem
         */
        private fun directoryPath(): Path? {
            try {
                if (local) {
                    return FileSystems.getDefault().getPath(url.path)
                }
            } catch (ignored:Exception) { }
            return null
        }

        override fun publish(metadata: InfoNode, artifacts: List<Pair<Path, String?>>): URI {
            val lock = directoryToLock()

            if (lock != null) {
                return directorySynchronized(lock) {
                    publishLocked(metadata, artifacts)
                }
            } else {
                return publishLocked(metadata, artifacts)
            }
        }

        private fun Path.checkValidForPublish(snapshot:Boolean) {
            if (Files.exists(this)) {
                if (snapshot) {
                    LOG.info("Overwriting {}", this)
                } else {
                    throw UnsupportedOperationException("Can't overwrite published non-snapshot file $this")
                }
            } else {
                Files.createDirectories(this.parent)
            }
        }

        private fun publishLocked(metadata: InfoNode, artifacts: List<Pair<Path, String?>>):URI {
            val path = directoryPath() ?: throw UnsupportedOperationException("Can't publish to non-local repository")

            val groupId = metadata.findChild("groupId")?.text ?: throw IllegalArgumentException("Metadata is missing a groupId:\n"+metadata)
            val artifactId = metadata.findChild("artifactId")?.text ?: throw IllegalArgumentException("Metadata is missing a artifactId:\n"+metadata)
            val version = metadata.findChild("version")?.text ?: throw IllegalArgumentException("Metadata is missing a version:\n"+metadata)

            val snapshot = version.endsWith("-SNAPSHOT")

            val pomPath = path / Maven2.pomPath(groupId, artifactId, version)
            LOG.debug("Publishing metadata to {}", pomPath)
            pomPath.checkValidForPublish(snapshot)
            val pomXML = metadata.toXML()
            Files.newBufferedWriter(pomPath, Charsets.UTF_8).use {
                it.append(pomXML)
            }
            // Create pom.xml hashes
            run {
                val pomXMLBytes = pomXML.toString().toByteArray(Charsets.UTF_8)
                for (checksum in PublishChecksums) {
                    val digest = checksum.digest()!!.digest(pomXMLBytes)

                    val publishedName = pomPath.name
                    val checksumFile = pomPath.parent.resolve("$publishedName${checksum.suffix}")
                    checksumFile.checkValidForPublish(snapshot)

                    checksumFile.writeText(createHashSum(digest, publishedName))
                }
            }


            for ((artifact, classifier) in artifacts) {
                val publishedArtifact = path / Maven2.artifactPath(groupId, artifactId, version, classifier, artifact.name.pathExtension())
                LOG.debug("Publishing {} to {}", artifact, publishedArtifact)
                publishedArtifact.checkValidForPublish(snapshot)

                Files.copy(artifact, publishedArtifact, StandardCopyOption.REPLACE_EXISTING)
                // Create hashes
                val checksums = PublishChecksums
                val digests = Array(checksums.size) { checksums[it].digest()!! }

                Files.newInputStream(artifact).use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        for (digest in digests) {
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                for (i in checksums.indices) {
                    val digest = digests[i].digest()

                    val publishedName = publishedArtifact.name
                    val checksumFile = publishedArtifact.parent.resolve("$publishedName${checksums[i].suffix}")
                    checksumFile.checkValidForPublish(snapshot)

                    checksumFile.writeText(createHashSum(digest, publishedName))
                }
            }

            return pomPath.parent.toUri()
        }

        companion object {
            private val LOG = LoggerFactory.getLogger(M2::class.java)

            /**
             * Various variants of the same dependency.
             * Examples: jdk15, sources, javadoc, linux
             */
            val Classifier = DependencyAttribute("m2-classifier", true)
            /**
             * Corresponds to the packaging of the dependency (and overrides it).
             * Determines what sort of artifact is retrieved.
             *
             * Examples: jar (default), pom (returns pom.xml, used internally)
             */
            val Type = DependencyAttribute("m2-type", true, "jar")
            /**
             * Scope of the dependency.
             *
             * Examples: compile, provided, test
             * See https://maven.apache.org/pom.html#Dependencies
             *
             * In Wemi used only when filtering.
             */
            val Scope = DependencyAttribute("m2-scope", false, "compile")
            /**
             * Optional dependencies are skipped by default by Wemi.
             */
            val Optional = DependencyAttribute("m2-optional", false, "false")

            /**
             * Concatenate two classifiers.
             */
            internal fun joinClassifiers(first:String?, second:String?):String? {
                when {
                    first == null -> return second
                    second == null -> return first
                    else -> return "$first-$second"
                }
            }

            /**
             * Classifier appended to artifacts with sources
             */
            const val SourcesClassifier = "sources"
            /**
             * Classifier appended to artifacts with Javadoc
             */
            const val JavadocClassifier = "javadoc"

            /**
             * [Checksum]s to generate when publishing an artifact.
             */
            val PublishChecksums = arrayOf(Checksum.MD5, Checksum.SHA1)
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
            fun digest():MessageDigest? {
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

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("M2: ").append(name).append(" at ").append(url)
            if (cache != null) {
                sb.append(" (cached by ").append(cache.name).append(')')
            }
            return sb.toString()
        }

        override fun writeMachine(json: Json) {
            json.writeObjectStart()
            json.writeValue("type", "M2", String::class.java)
            json.writeValue("url", url.toExternalForm(), String::class.java)
            json.writeValue("local", local, Boolean::class.java)
            json.writeValue("cache", cache, M2::class.java)
            json.writeValue("checksum", checksum, M2.Checksum::class.java)
            json.writeObjectEnd()
        }
    }

    override fun toString(): String {
        return "Repository: $name"
    }
}

/** Special collection of repositories in preferred order and with cache repositories inlined. */
typealias RepositoryChain = Collection<Repository>

/** Sorts repositories into an efficient chain.
 * Inlines cache repositories so that they are checked first.
 * Otherwise tries to maintain original order. */
fun createRepositoryChain(repositories: Collection<Repository>): RepositoryChain {
    val list = mutableListOf<Repository>()
    list.addAll(repositories)

    // Inline cache into the list
    for (repository in repositories) {
        list.add(repository.cache ?: continue)
    }

    // Sort to search local/cache first
    list.sortWith(Comparator { first, second ->
        if (first.local && !second.local) {
            -1
        } else if (!first.local && second.local) {
            1
        } else {
            0
        }
    })

    // Remove duplicates
    val seen = HashSet<Repository>()
    list.removeAll { repository ->
        val justAdded = seen.add(repository)
        !justAdded
    }
    return list
}

// Default repositories
/**
 * Local Maven repository stored in ~/.m2/repository
 */
val LocalM2Repository = Repository.M2("local", URL("file", "localhost", System.getProperty("user.home") + "/.m2/repository/"), null)
/**
 * Maven Central repository at [maven.org](https://maven.org)
 *
 * Cached by [LocalM2Repository].
 */
val MavenCentral = Repository.M2("central", URL("https://repo1.maven.org/maven2/"), LocalM2Repository)

/**
 * JCenter repository at [bintray.com](https://bintray.com/bintray/jcenter)
 *
 * Cached by [LocalM2Repository].
 */
val JCenter = Repository.M2("jcenter", URL("https://jcenter.bintray.com/"), LocalM2Repository)
/**
 * Jitpack repository at [jitpack.io](https://jitpack.io)
 *
 * Cached by [LocalM2Repository].
 */
@Suppress("unused")
val Jitpack = Repository.M2("jitpack", URL("https://jitpack.io/"), LocalM2Repository)

/**
 * Repositories to use by default.
 *
 * @see MavenCentral
 * @see LocalM2Repository (included as cache of [MavenCentral])
 */
val DefaultRepositories: WSet<Repository> = wSetOf(MavenCentral)