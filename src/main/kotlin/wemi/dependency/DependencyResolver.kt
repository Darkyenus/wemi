package wemi.dependency

import org.slf4j.LoggerFactory
import java.io.File

/**
 *
 */
object DependencyResolver {

    private val LOG = LoggerFactory.getLogger(DependencyResolver.javaClass)

    fun resolveProject(projectDependency: ProjectDependency, repositories: RepositoryChain): ResolvedProject {
        LOG.debug("Resolving {}", projectDependency)
        // Try preferred repository first
        if (projectDependency.project.preferredRepository != null) {
            val resolved = resolveInRepository(projectDependency, projectDependency.project.preferredRepository)
            if (!resolved.hasError) {
                LOG.debug("Resolution success {}", resolved)
                return resolved
            }
        }
        // Try ordered repositories
        for (repository in repositories) {
            val resolved = resolveInRepository(projectDependency, repository)
            if (!resolved.hasError) {
                LOG.debug("Resolution success {}", resolved)
                return resolved
            }
        }
        // Fail
        LOG.warn("Failed to resolve {}", projectDependency.project)//TODO make this debug and report elsewhere
        return ResolvedProject(projectDependency.project, null, emptyList(), true)
    }

    private fun doResolveArtifacts(resolved: MutableMap<ProjectId, ResolvedProject>,
                                   dependency: ProjectDependency, repositories: RepositoryChain,
                                   mapper:(ProjectDependency) -> ProjectDependency): Boolean {
        if (resolved.contains(dependency.project)) {
            return true
        }

        val resolvedProject = resolveProject(mapper(dependency), repositories)
        if (resolvedProject.hasError) {
            return false
        }
        resolved.put(dependency.project, resolvedProject)

        var ok = true
        for (transitiveDependency in resolvedProject.dependencies) {
            ok = ok and doResolveArtifacts(resolved, transitiveDependency, repositories, mapper)
        }
        return ok
    }

    fun resolveArtifacts(projects: Collection<ProjectDependency>, repositories: RepositoryChain): List<File>? {
        val resolved = mutableMapOf<ProjectId, ResolvedProject>()
        val ok = resolve(resolved, projects, repositories, {it})

        if (!ok) {
            return null
        }

        return resolved.mapNotNull { it.value.artifact }
    }

    fun resolve(resolved:MutableMap<ProjectId, ResolvedProject>, projects: Collection<ProjectDependency>, repositories: RepositoryChain, mapper:((ProjectDependency) -> ProjectDependency)): Boolean {
        var ok = true

        for (project in projects) {
            ok = ok and doResolveArtifacts(resolved, project, repositories, mapper)
        }

        return ok
    }

    private fun resolveInRepository(projectDependency: ProjectDependency, repository: Repository): ResolvedProject {
        LOG.debug("Trying in {}", repository)

        return when (repository) {
            is Repository.M2 -> {
                MavenDependencyResolver.resolveInM2Repository(projectDependency, repository)
            }
        }
    }
}