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
class ProjectDependency(val project: Project, vararg val configurations: Configuration, val scope:DepScope = ScopeCompile)
    : JsonWritable {

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectDependency) return false

        if (project != other.project) return false
        if (!configurations.contentEquals(other.configurations)) return false
        if (scope != other.scope) return false

        return true
    }

    override fun hashCode(): Int {
        var result = project.hashCode()
        result = 31 * result + configurations.contentHashCode()
        result = 31 * result + scope.hashCode()
        return result
    }
}
