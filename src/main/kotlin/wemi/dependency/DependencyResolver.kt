package wemi.dependency

import org.slf4j.LoggerFactory
import java.io.File

/**
 *
 */
object DependencyResolver {

    private val LOG = LoggerFactory.getLogger(DependencyResolver.javaClass)

    fun resolveSingleDependency(dependency: Dependency, repositories: RepositoryChain): ResolvedDependency {
        LOG.debug("Resolving {}", dependency)
        // Try preferred repository first
        if (dependency.dependencyId.preferredRepository != null) {
            val resolved = resolveInRepository(dependency, dependency.dependencyId.preferredRepository)
            if (!resolved.hasError) {
                LOG.debug("Resolution success {}", resolved)
                return resolved
            }
        }
        // Try ordered repositories
        for (repository in repositories) {
            val resolved = resolveInRepository(dependency, repository)
            if (!resolved.hasError) {
                LOG.debug("Resolution success {}", resolved)
                return resolved
            }
        }
        // Fail
        LOG.warn("Failed to resolve {}", dependency.dependencyId)//TODO make this debug and report elsewhere
        return ResolvedDependency(dependency.dependencyId, null, emptyList(), true)
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
        val ok = resolve(resolved, projects, repositories, {it})

        if (!ok) {
            return null
        }

        return resolved.mapNotNull { it.value.artifact }
    }

    fun resolve(resolved:MutableMap<DependencyId, ResolvedDependency>, projects: Collection<Dependency>, repositories: RepositoryChain, mapper:((Dependency) -> Dependency)): Boolean {
        var ok = true

        for (project in projects) {
            ok = ok and doResolveArtifacts(resolved, project, repositories, mapper)
        }

        return ok
    }

    private fun resolveInRepository(dependency: Dependency, repository: Repository): ResolvedDependency {
        LOG.debug("Trying in {}", repository)

        return when (repository) {
            is Repository.M2 -> {
                MavenDependencyResolver.resolveInM2Repository(dependency, repository)
            }
        }
    }
}