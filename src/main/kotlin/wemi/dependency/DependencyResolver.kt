package wemi.dependency

import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Provides entry points to the DependencyResolver API for resolving library dependencies.
 */
object DependencyResolver {

    private val LOG = LoggerFactory.getLogger(DependencyResolver.javaClass)

    fun resolveSingleDependency(dependency: Dependency, repositories: RepositoryChain): ResolvedDependency {
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

    private fun doResolveArtifacts(resolved: MutableMap<DependencyId, ResolvedDependency>,
                                   dependency: Dependency, repositories: RepositoryChain,
                                   mapper: (Dependency) -> Dependency): Boolean {
        if (resolved.contains(dependency.dependencyId)) {
            return true
        }

        val resolvedProject = resolveSingleDependency(mapper(dependency), repositories)
        resolved.put(dependency.dependencyId, resolvedProject)

        var ok = !resolvedProject.hasError
        for (transitiveDependency in resolvedProject.dependencies) {
            if (!doResolveArtifacts(resolved, transitiveDependency, repositories, mapper)) {
                ok = false
            }
        }
        return ok
    }

    fun resolveArtifacts(projects: Collection<Dependency>, repositories: RepositoryChain): List<Path>? {
        val resolved = mutableMapOf<DependencyId, ResolvedDependency>()
        val ok = resolve(resolved, projects, repositories)

        if (!ok) {
            return null
        }

        return resolved.artifacts()
    }

    fun Map<DependencyId, ResolvedDependency>.artifacts(): List<Path> {
        return mapNotNull { it.value.artifact }
    }

    fun resolve(resolved: MutableMap<DependencyId, ResolvedDependency>, projects: Collection<Dependency>, repositories: RepositoryChain, mapper: ((Dependency) -> Dependency) = { it }): Boolean {
        var ok = true

        for (project in projects) {
            if (!doResolveArtifacts(resolved, project, repositories, mapper)) {
                ok = false
            }
        }

        return ok
    }

}