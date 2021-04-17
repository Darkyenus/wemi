package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonWriter
import wemi.Configuration
import wemi.Project
import wemi.util.JsonWritable
import wemi.util.field
import wemi.util.writeArray
import wemi.util.writeObject
import wemi.util.writeValue

/**
 * Defines a dependency on a [project] defined in the same build script.
 *
 * Dependency pulls [project]s [wemi.keys.internalClasspath] and [wemi.keys.externalClasspath] into
 * this project's external classpath.
 *
 * To create an aggregate project dependency, set [scope] to [ScopeAggregate].
 * Aggregated project's internal classpath will end up in this projects archive, as if it was on its internal classpath.
 * Non-aggregate projects behave like normal libraries, archived separately.
 */
data class ProjectDependency(val project: Project, val configurations: List<Configuration>, val scope:DepScope = ScopeCompile)
    : JsonWritable {

    constructor(project:Project, vararg configurations: Configuration, scope:DepScope = ScopeCompile) : this(project, listOf(*configurations), scope)

    @Deprecated("Use ScopeAggregate instead")
    constructor(project: Project, aggregate:Boolean, vararg configurations: Configuration, scope:DepScope = ScopeCompile) : this(project, *configurations, scope = if (aggregate) ScopeAggregate else scope)

    override fun JsonWriter.write() {
        writeObject {
            field("project", project.name)
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
        sb.append(" scope=").append(scope)

        return sb.toString()
    }
}
