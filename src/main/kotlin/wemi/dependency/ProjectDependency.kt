package wemi.dependency

import wemi.Configuration
import wemi.Project

/**
 * Defines a dependency on a project defined in the same build script.
 *
 * Dependency pulls [project]s [wemi.Keys.internalClasspath] and [wemi.Keys.externalClasspath] into
 * this project's external classpath.
 *
 * @see dependency
 */
class ProjectDependency internal constructor(val project: Project, val configurations:Array<out Configuration>)

/**
 * Create a ProjectDependency for depending on this [Project], optionally with given configurations on top.
 */
fun Project.dependency(vararg configurations:Configuration):ProjectDependency {
    return ProjectDependency(this, configurations)
}