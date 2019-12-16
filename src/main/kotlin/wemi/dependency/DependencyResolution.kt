package wemi.dependency

import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.util.Partial
import wemi.util.appendShortByteSize
import wemi.util.directorySynchronized
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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

private object LoggingDownloadTracker : ActivityListener {

    private val activityStack = ArrayList<String>()
    private var downloadStatuses = ArrayList<DownloadStatus?>()

    override fun beginActivity(activity: String) {
        activityStack.add(activity)
        downloadStatuses.add(null)
    }

    override fun activityDownloadProgress(bytes: Long, totalBytes: Long, durationNs: Long) {
        if (!LOG.isInfoEnabled) {
            return
        }

        val downloadStatuses = downloadStatuses
        var downloadStatus:DownloadStatus? = downloadStatuses.last()
        if (downloadStatus == null) {
            downloadStatus = DownloadStatus()
            downloadStatuses[downloadStatuses.lastIndex] = downloadStatus
        }

        downloadStatus.lastDownloadBytes = bytes

        // We only log if a lot of time elapsed since the last log
        if (downloadStatus.lastLogAtDurationNs + TimeUnit.SECONDS.toNanos(3) >= durationNs) return
        downloadStatus.lastLogAtDurationNs = durationNs

        val sb = StringBuilder()
        sb.appendShortByteSize(bytes).append('/')
        if (totalBytes > 0 && bytes < totalBytes) {
            sb.appendShortByteSize(totalBytes)
        } else {
            sb.append("???")
        }
        LOG.info("Downloading {} ({})", activityStack.last(), sb)
    }

    override fun endActivity() {
        activityStack.removeAt(activityStack.lastIndex)
        downloadStatuses.removeAt(downloadStatuses.lastIndex)
    }

    private class DownloadStatus {
        var lastLogAtDurationNs:Long = Long.MIN_VALUE
        var lastDownloadBytes:Long = 0
    }
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
    for (repository in repositories) {
        directoriesToLock.add(repository.cache ?: continue)
    }

    return directorySynchronized(directoriesToLock, { dir ->
        // On wait
        LOG.info("Waiting for lock on {}", dir)
    }) {
        wemi.dependency.internal.resolveArtifacts(dependencies, sorted, mapper, progressListener ?: LoggingDownloadTracker)
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