package wemi.dependency

import com.esotericsoftware.jsonbeans.Json
import wemi.Configuration
import wemi.Project
import wemi.boot.MachineWritable

/**
 * Defines a dependency on a project defined in the same build script.
 *
 * Dependency pulls [project]s [wemi.Keys.internalClasspath] and [wemi.Keys.externalClasspath] into
 * this project's external classpath.
 *
 * @see dependency
 */
class ProjectDependency internal constructor(val project: Project, val configurations: Array<out Configuration>)
    : MachineWritable {

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("project", project.name, String::class.java)
        json.writeArrayStart("configurations")
        for (configuration in configurations) {
            json.writeValue(configuration.name as Any, String::class.java)
        }
        json.writeArrayEnd()
        json.writeObjectEnd()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(project.name).append('/')
        for (c in configurations) {
            sb.append(c.name).append(':')
        }

        return sb.toString()
    }
}

/**
 * Create a ProjectDependency for depending on this [Project], optionally with given configurations on top.
 */
fun dependency(project:Project, vararg configurations: Configuration): ProjectDependency {
    return ProjectDependency(project, configurations)
}