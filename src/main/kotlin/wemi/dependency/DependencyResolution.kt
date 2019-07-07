package wemi.dependency

import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.util.Partial
import wemi.util.directorySynchronized
import java.nio.file.Path

/**
 * Utility method to [resolveDependencies] dependencies and retrieve their [artifacts].
 *
 * If any dependency fails to resolve, returns null.
 */
fun resolveDependencyArtifacts(dependencies: Collection<Dependency>, repositories: Collection<Repository>, progressListener:ActivityListener?): List<Path>? {
    val (resolved, ok ) = resolveDependencies(dependencies, repositories, progressListener = progressListener)

    if (!ok) {
        return null
    }

    return resolved.artifacts()
}

/**
 * Retrieve all artifacts from [ResolvedDependency].
 * Skips those without artifact. Does not check error status or anything else.
 */
fun Map<DependencyId, ResolvedDependency>.artifacts(): List<Path> {
    return mapNotNull { it.value.artifact?.path }
}

/**
 * Resolve [dependencies] for their artifacts, including transitive.
 * Resolution is done using [repositories] and their cache.
 * Actually resolved dependencies can be at any point modified with [mapper].
 *
 * This is the entry point to dependency resolution.
 *
 * @return true if all [dependencies] resolve correctly without error
 */
fun resolveDependencies(dependencies: Collection<Dependency>,
                        repositories: Collection<Repository>,
                        mapper: ((Dependency) -> Dependency) = { it },
                        progressListener: ActivityListener? = null): Partial<Map<DependencyId, ResolvedDependency>> {
    // Sort repositories
    val sorted = ArrayList(repositories)
    sorted.sortWith(REPOSITORY_COMPARATOR)

    // Lock repositories
    val directoriesToLock = HashSet<Path>()
    for (repository in repositories) {// TODO(jp): Only lock caches, this process does not touch local repositories
        directoriesToLock.add(repository.directoryPath() ?: continue)
    }

    fun <Result> locked(iterator:Iterator<Path>, action:()->Result):Result {
        return if (!iterator.hasNext()) {
            action()
        } else {
            val directory = iterator.next()
            directorySynchronized(directory, {
                // On wait
                LOG.info("Waiting for lock on {}", directory)
            }) {
                locked(iterator, action)
            }
        }
    }

    return locked(directoriesToLock.iterator()) {
        wemi.dependency.internal.resolveArtifacts(dependencies, sorted, mapper, progressListener)
    }
}

// -------------------------------- Internal ------------------------------

private val LOG = LoggerFactory.getLogger("LibraryDependencyResolver")
/** Repositories which are sorted for efficiency. Should be treated as opaque. */
internal typealias SortedRepositories = List<Repository>
/** Like [SortedRepositories], but contains only valid repositories for given snapshot state. */
internal typealias CompatibleSortedRepositories = SortedRepositories

/** Compares repositories so that local repositories are first. */
private val REPOSITORY_COMPARATOR = Comparator<Repository> { o1, o2 ->
    val cache1 = o1.cache
    val cache2 = o2.cache
    // Locals go first (cache == null <-> local)
    if (cache1 == null && cache2 != null) {
        -1
    } else if (cache1 != null && cache2 == null) {
         1
    } else {
        // Arbitrary sorting by name to get full ordering
        o1.name.compareTo(o2.name)
    }
}