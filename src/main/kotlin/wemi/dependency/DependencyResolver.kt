package wemi.dependency

import org.slf4j.LoggerFactory
import java.io.File

/**
 *
 */
object DependencyResolver {

    private val LOG = LoggerFactory.getLogger(DependencyResolver.javaClass)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun StringBuilder?.tried(repo:Repository):StringBuilder {
        val sb = this?: StringBuilder()

        if (sb.isEmpty()) {
            sb.append("tried: ").append(repo.name)
        } else {
            sb.append(", ").append(repo.name)
        }

        return sb
    }

    fun resolveSingleDependency(dependency: Dependency, repositories: RepositoryChain): ResolvedDependency {
        var log:StringBuilder? = null

        LOG.debug("Resolving {}", dependency)
        // Try preferred repository first
        if (dependency.dependencyId.preferredRepository != null) {
            LOG.debug("Trying in {}", dependency.dependencyId.preferredRepository)
            val resolved = dependency.dependencyId.preferredRepository.resolveInRepository(dependency, repositories)
            if (!resolved.hasError) {
                LOG.debug("Resolution success {}", resolved)
                return resolved
            } else {
                log = log.tried(dependency.dependencyId.preferredRepository)
            }
        }
        // Try ordered repositories
        for (repository in repositories) {
            LOG.debug("Trying in {}", repository)
            val resolved = repository.resolveInRepository(dependency, repositories)
            if (!resolved.hasError) {
                LOG.debug("Resolution success {}", resolved)
                return resolved
            } else {
                log = log.tried(repository)
            }
        }

        // Fail
        LOG.debug("Failed to resolve {}", dependency.dependencyId)
        return ResolvedDependency(dependency.dependencyId, emptyList(), null, true, log?:"no repositories to search in")
    }

    private fun doResolveArtifacts(resolved: MutableMap<DependencyId, ResolvedDependency>,
                                   dependency: Dependency, repositories: RepositoryChain,
                                   mapper:(Dependency) -> Dependency): Boolean {
        if (resolved.contains(dependency.dependencyId)) {
            return true
        }

        val resolvedProject = resolveSingleDependency(mapper(dependency), repositories)
        if (resolvedProject.hasError) {
            return false
        }
        resolved.put(dependency.dependencyId, resolvedProject)

        var ok = true
        for (transitiveDependency in resolvedProject.dependencies) {
            ok = ok and doResolveArtifacts(resolved, transitiveDependency, repositories, mapper)
        }
        return ok
    }

    fun resolveArtifacts(projects: Collection<Dependency>, repositories: RepositoryChain): List<File>? {
        val resolved = mutableMapOf<DependencyId, ResolvedDependency>()
        val ok = resolve(resolved, projects, repositories)

        if (!ok) {
            return null
        }

        return resolved.artifacts()
    }

    fun Map<DependencyId, ResolvedDependency>.artifacts():List<File> {
        return mapNotNull { it.value.artifact }
    }

    fun resolve(resolved:MutableMap<DependencyId, ResolvedDependency>, projects: Collection<Dependency>, repositories: RepositoryChain, mapper:((Dependency) -> Dependency) = {it}): Boolean {
        var ok = true

        for (project in projects) {
            ok = ok and doResolveArtifacts(resolved, project, repositories, mapper)
        }

        return ok
    }

}