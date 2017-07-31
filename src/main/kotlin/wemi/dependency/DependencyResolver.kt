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
        LOG.warn("Failed to resolve {}", projectDependency.project)
        return ResolvedProject(projectDependency.project, null, emptyList(), true)
    }

    private fun doResolveArtifacts(artifacts: MutableList<File>, resolved: MutableSet<ProjectId>,
                                   dependency: ProjectDependency, repositories: RepositoryChain): Boolean {
        if (resolved.contains(dependency.project)) {
            return true
        }

        val resolvedProject = resolveProject(dependency, repositories)
        if (resolvedProject.hasError) {
            return false
        }
        resolved.add(resolvedProject.id)
        if (resolvedProject.artifact != null) {
            artifacts.add(resolvedProject.artifact)
        }

        var ok = true
        for (transitiveDependency in resolvedProject.dependencies) {
            ok = ok and doResolveArtifacts(artifacts, resolved, transitiveDependency, repositories)
        }
        return ok
    }

    fun resolveArtifacts(projects: Collection<ProjectDependency>, repositories: RepositoryChain): List<File>? {
        val artifacts = mutableListOf<File>()
        val resolved = mutableSetOf<ProjectId>()

        var ok = true

        for (project in projects) {
            ok = ok and doResolveArtifacts(artifacts, resolved, project, repositories)
        }

        if (!ok) {
            return null
        }

        return artifacts
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