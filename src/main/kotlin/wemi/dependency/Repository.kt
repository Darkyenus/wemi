package wemi.dependency

import com.esotericsoftware.jsonbeans.Json
import wemi.boot.MachineWritable
import java.net.URL
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
    abstract fun resolveInRepository(dependency: Dependency, chain: RepositoryChain): ResolvedDependency

    /** Maven repository.
     *
     * @param name of this repository, arbitrary
     * @param url of this repository
     * @param cache of this repository
     * @param checksum to use when retrieving artifacts from here
     */
    class M2(name: String, val url: URL, override val cache: M2? = null, val checksum: Checksum = M2.Checksum.SHA1) : Repository(name) {
        override val local: Boolean
            get() = cache == null

        override fun resolveInRepository(dependency: Dependency, chain: RepositoryChain): ResolvedDependency {
            return MavenDependencyResolver.resolveInM2Repository(dependency, this, chain)
        }

        companion object {
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
        }

        /**
         * Types of checksum in Maven repositories.
         *
         * @param suffix of files with this checksum
         * @param algo Java digest algorithm name to use when computing this checksum
         */
        enum class Checksum(val suffix: String, private val algo: String) {
            /**
             * Special value for no checksum.
             *
             * Not recommended for general use - use only in extreme cases.
             */
            None(".no-checksum", "no-op"),
            /**
             * Standard SHA1 algorithm with .sha1 suffix.
             */
            SHA1(".sha1", "SHA-1");

            fun checksum(data: ByteArray): ByteArray {
                if (this == None) {
                    return kotlin.ByteArray(0)
                }
                val digest = MessageDigest.getInstance(algo)
                digest.reset()
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
        if (!list.contains(repository.cache ?: continue)) {
            list.add(repository)
        }
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
    var lastRepository: Repository? = null
    list.removeAll { repository ->
        if (repository == lastRepository) {
            true
        } else {
            lastRepository = repository
            false
        }
    }
    return list
}

// Default repositories
/**
 * Local Maven repository stored in ~/.m2/repository
 */
val LocalM2Repository = Repository.M2("local", URL("file", "localhost", System.getProperty("user.home") + "/.m2/repository/"), null)
/**
 * Maven Central repository: https://maven.org
 *
 * Cached by [LocalM2Repository].
 */
val MavenCentral = Repository.M2("central", URL("https://repo1.maven.org/maven2/"), LocalM2Repository)

/**
 * Repositories to use by default.
 *
 * @see LocalM2Repository
 * @see MavenCentral
 */
val DefaultRepositories: List<Repository> = listOf(
        /* It would seem that local is added twice here (2nd as a cache of local),
         but that is semantically correct, because LocalM2Repository is not only a cache. */
        LocalM2Repository,
        MavenCentral
)