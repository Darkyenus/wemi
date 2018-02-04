package wemi.dependency

import org.slf4j.LoggerFactory
import wemi.util.directorySynchronized
import java.nio.file.Path

/**
 * Provides entry points to the DependencyResolver API for resolving library dependencies.
 */
object DependencyResolver {

    private val LOG = LoggerFactory.getLogger(DependencyResolver.javaClass)

    /**
     * Resolve [dependency] using the [repositories] repository chain.
     * [DependencyId.preferredRepository] and its cache is tried first.
     *
     * Does not resolve transitively.
     * When resolution fails, returns ResolvedDependency with [ResolvedDependency.hasError] = true.
     */
    internal fun resolveSingleDependency(dependency: Dependency, repositories: RepositoryChain): ResolvedDependency {
        var log: StringBuilder? = null

        LOG.debug("Resolving {}", dependency)

        fun resolveInRepository(repository: Repository?): ResolvedDependency? {
            if (repository == null) {
                return null
            }

            LOG.debug("Trying in {}", repository)
            val resolved = repository.resolveInRepository(dependency, repositories)
            if (!resolved.hasError) {
                LOG.debug("Resolution success {}", resolved)
                return resolved
            } else {
                val sb = log ?: StringBuilder()
                if (sb.isEmpty()) {
                    sb.append("tried: ").append(repository.name)
                } else {
                    sb.append(", ").append(repository.name)
                }
                log = sb
            }

            return null
        }

        // Try preferred repository and its cache first
        val preferred = (resolveInRepository(dependency.dependencyId.preferredRepository?.cache)
                ?: resolveInRepository(dependency.dependencyId.preferredRepository))
        if (preferred != null) {
            return preferred
        }

        // Try ordered repositories
        for (repository in repositories) {
            // Skip preferred repositories as those were already tried
            if (repository == dependency.dependencyId.preferredRepository
                    || repository == dependency.dependencyId.preferredRepository?.cache) {
                continue
            }

            resolveInRepository(repository)?.let { return it }
        }

        // Fail
        LOG.debug("Failed to resolve {}", dependency.dependencyId)
        return ResolvedDependency(dependency.dependencyId, emptyList(), null, true, log ?: "no repositories to search in")
    }

    /**
     * Internal.
     *
     * Resolves artifacts for [dependency], using [resolveSingleDependency],
     * and artifacts for transitive dependencies by calling itself.
     *
     * Remembers errors, but does not stop on them and tries to resolve as much as possible.
     *
     * @param mapper to modify which dependency is actually resolved
     * @param resolved cache for already resolved dependencies
     * @param repositories to use
     * @return true if all dependencies resolved without error
     */
    private fun doResolveArtifacts(resolved: MutableMap<DependencyId, ResolvedDependency>,
                                   dependency: Dependency, repositories: RepositoryChain,
                                   mapper: (Dependency) -> Dependency): Boolean {
        if (resolved.contains(dependency.dependencyId)) {
            return true
        }

        val resolvedProject = resolveSingleDependency(mapper(dependency), repositories)
        resolved[dependency.dependencyId] = resolvedProject

        var ok = !resolvedProject.hasError
        for (transitiveDependency in resolvedProject.dependencies) {
            if (!doResolveArtifacts(resolved, transitiveDependency, repositories, mapper)) {
                ok = false
            }
        }
        return ok
    }

    /**
     * Utility method to [resolve] dependencies and retrieve their [artifacts].
     *
     * If any dependency fails to resolve, returns null.
     */
    fun resolveArtifacts(projects: Collection<Dependency>, repositories: RepositoryChain): List<Path>? {
        val resolved = mutableMapOf<DependencyId, ResolvedDependency>()
        val ok = resolve(resolved, projects, repositories)

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
        return mapNotNull { it.value.artifact }
    }

    /**
     * Resolve [dependencies] and store what was resolved in [resolved].
     * Resolution is done using [repositories] and using [DependencyId.preferredRepository] and its cache, if any.
     * Actually resolved dependencies can be at any point modified with [mapper].
     *
     * This is the entry point to dependency resolution.
     *
     * @return true if all [dependencies] resolve correctly without error
     */
    fun resolve(resolved: MutableMap<DependencyId, ResolvedDependency>, dependencies: Collection<Dependency>, repositories: RepositoryChain, mapper: ((Dependency) -> Dependency) = { it }): Boolean {
        val directoriesToLock = repositories.mapNotNull { it.cache?.directoryToLock() }.distinct()

        fun <Result> locked(level:Int, action:()->Result):Result {
            if (level == directoriesToLock.size) {
                return action()
            } else {
                val directory = directoriesToLock[level]
                return directorySynchronized(directory, {
                    // On wait
                    LOG.info("Waiting for lock on {}", directory)
                }) {
                    locked(level + 1, action)
                }
            }
        }

        return locked(0) {
            var ok = true
            for (project in dependencies) {
                if (!doResolveArtifacts(resolved, project, repositories, mapper)) {
                    ok = false
                }
            }
            ok
        }
    }

}