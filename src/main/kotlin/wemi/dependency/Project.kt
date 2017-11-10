package wemi.dependency

import com.esotericsoftware.jsonbeans.Json
import wemi.boot.MachineWritable
import java.io.File

/**
 * Unique identifier for wemi.project/module to be resolved.
 * May have dependencies on other projects and may have artifacts.
 *
 * @param group of wemi.project (aka organisation)
 * @param name of wemi.project
 * @param version of wemi.project (aka revision)
 *
 * @param preferredRepository preferredRepository in which to search for this wemi.project first
 */
data class ProjectId(val group: String,
                val name: String,
                val version: String,

                val preferredRepository: Repository? = null,
                val attributes: Map<ProjectAttribute, String> = emptyMap()) : MachineWritable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as ProjectId

        if (group != other.group) return false
        if (name != other.name) return false
        if (version != other.version) return false
        for ((key, value) in attributes) {
            if (key.makesUnique && other.attributes[key] != value) return false
        }
        for ((key, value) in other.attributes) {
            if (key.makesUnique && attributes[key] != value) return false
        }
        if (attributes != other.attributes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = group.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        for ((key, value) in attributes) {
            if (key.makesUnique) {
                result = 31 * result + key.hashCode()
                result = 31 * result + value.hashCode()
            }
        }
        return result
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append(group).append(':').append(name).append(':').append(version)
        if (preferredRepository != null) {
            result.append('@').append(preferredRepository)
        }
        if (attributes.isNotEmpty()) {
            result.append(' ').append(attributes)
        }
        return result.toString()
    }

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("group", group, String::class.java)
        json.writeValue("name", name, String::class.java)
        json.writeValue("version", version, String::class.java)

        json.writeValue("preferredRepository", preferredRepository, Repository::class.java)
        json.writeValue("attributes", attributes, Map::class.java)
        json.writeObjectEnd()
    }
}

data class ProjectAttribute(val name: String, val makesUnique: Boolean) : MachineWritable {
    override fun toString(): String = name

    override fun writeMachine(json: Json) {
        json.writeValue(name as Any, String::class.java)
    }
}

/** Represents exclusion rule. All fields must match precisely to be considered for exclusion.
 * [group], [name] and [version] may contain "*" to signify wildcard that matches all.
 * Such wildcard in attribute means presence of that attribute, with any value. */
data class ProjectExclusion(val group: String, val name: String, val version: String, val attributes: Map<ProjectAttribute, String> = emptyMap()) : MachineWritable {

    private fun matches(pattern: String, value: String?): Boolean {
        return (pattern == "*" && value != null) || pattern == value
    }

    fun excludes(projectId: ProjectId): Boolean {
        return matches(group, projectId.group)
                && matches(name, projectId.name)
                && matches(version, projectId.version)
                && attributes.all { (key, value) -> matches(value, projectId.attributes[key]) }
    }

    override fun toString(): String {
        return "$group:$name:$version $attributes"
    }

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("group", group, String::class.java)
        json.writeValue("name", name, String::class.java)
        json.writeValue("version", version, String::class.java)

        json.writeValue("attributes", attributes, Map::class.java)
        json.writeObjectEnd()
    }
}

val DefaultExclusions = listOf(
        ProjectExclusion("*", "*", "*", mapOf(
                Repository.M2.M2OptionalAttribute to "true"
        )),
        ProjectExclusion("*", "*", "*", mapOf(
                Repository.M2.M2ScopeAttribute to "provided"
        )),
        ProjectExclusion("*", "*", "*", mapOf(
                Repository.M2.M2ScopeAttribute to "test"
        )),
        ProjectExclusion("*", "*", "*", mapOf(
                Repository.M2.M2ScopeAttribute to "system"
        ))
)

/** Represents dependency on a [project], with transitive dependencies, which may be excluded by [exclusions]. */
data class ProjectDependency(val project: ProjectId, val exclusions: List<ProjectExclusion> = DefaultExclusions) : MachineWritable {
    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("project", project, ProjectId::class.java)
        json.writeValue("exclusions", exclusions, List::class.java)
        json.writeObjectEnd()
    }
}

/**
 * Data retrieved by resolving a wemi.project
 */
data class ResolvedProject(val id: ProjectId, val artifact: File?, val dependencies: List<ProjectDependency>, val hasError: Boolean)