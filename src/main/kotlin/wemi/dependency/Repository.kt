package wemi.dependency

import com.esotericsoftware.jsonbeans.Json
import wemi.boot.MachineWritable
import java.net.URL
import java.security.MessageDigest

/**
 * Represents preferredRepository from which artifacts may be retrieved
 */
sealed class Repository(val name: String) : MachineWritable {

    /** Local repositories are preferred, because retrieving from them is faster. */
    abstract val local: Boolean

    /** Repository acting as a cache for this preferredRepository. Always searched first. */
    abstract val cache: Repository?

    /**
     * Attempt to resolve given dependency in this repository.
     *
     * @param dependency to resolve
     * @param chain of repositories which may be queried for looking up dependencies
     */
    abstract fun resolveInRepository(dependency: Dependency, chain:RepositoryChain):ResolvedDependency

    /** Maven preferredRepository. */
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
            val M2ClassifierAttribute = DependencyAttribute("m2-classifier", true)
            /**
             * Corresponds to the packaging of the dependency (and overrides it).
             * Determines what sort of artifact is retrieved.
             *
             * Examples: jar (default), pom (returns pom.xml, used internally)
             */
            val M2TypeAttribute = DependencyAttribute("m2-type", true)
            val M2ScopeAttribute = DependencyAttribute("m2-scope", false)
            val M2OptionalAttribute = DependencyAttribute("m2-optional", false)
        }

        enum class Checksum(val suffix: String, val algo: String) {
            None(".no-checksum", "no-op"),
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

/** Sorts repositories into an efficient chain */
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
val LocalM2Repository = Repository.M2("local", URL("file", "localhost", System.getProperty("user.home") + "/.m2/repository/"), null)
val CentralM2Repository = Repository.M2("central", URL("https://repo1.maven.org/maven2/"), LocalM2Repository)

val DefaultRepositories: List<Repository> = listOf(
        LocalM2Repository,
        CentralM2Repository
)