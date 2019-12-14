package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonWriter
import wemi.Configuration
import wemi.Project
import wemi.util.*

/**
 * Defines a dependency on a [project] defined in the same build script.
 *
 * Dependency pulls [project]s [wemi.Keys.internalClasspath] and [wemi.Keys.externalClasspath] into
 * this project's external classpath.
 *
 * @param aggregate if `true`, [project]'s internal classpath will be considered a part of this project's
 *          internal classpath when creating artifact archive. If `false`, this dependency will have to be
 *          represented through metadata and both projects will have to be archived separately.
 */
class ProjectDependency(val project: Project, val aggregate:Boolean, vararg val configurations: Configuration, val scope:Scope = DEFAULT_SCOPE)
    : JsonWritable {

    override fun JsonWriter.write() {
        writeObject {
            field("project", project.name)
            field("aggregate", aggregate)
            field("scope", scope)
            name("configurations").writeArray {
                for (configuration in configurations) {
                    writeValue(configuration.name, String::class.java)
                }
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(project.name).append('/')
        for (c in configurations) {
            sb.append(c.name).append(':')
        }
        if (aggregate) {
            sb.append(" aggregate")
        }
        sb.append(" scope=").append(scope)

        return sb.toString()
    }
}
