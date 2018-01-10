package wemi.dependency

import com.esotericsoftware.jsonbeans.Json
import org.slf4j.LoggerFactory
import wemi.boot.MachineWritable
import wemi.collections.TinyMap
import wemi.util.readBytes
import java.nio.file.Path

/**
 * Unique identifier for project/module to be resolved.
 * May have dependencies on other projects and may have artifacts.
 *
 * @param group of project (aka organisation)
 * @param name of project
 * @param version of project (aka revision)
 *
 * @param preferredRepository preferredRepository in which to search for this project first
 *          (its cache, if any, is searched even earlier)
 */
data class DependencyId(val group: String,
                        val name: String,
                        val version: String,

                        val preferredRepository: Repository? = null,
                        val attributes: Map<DependencyAttribute, String> = emptyMap()) : MachineWritable {

    fun attribute(attribute: DependencyAttribute):String? {
        return attributes[attribute] ?: attribute.defaultValue
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as DependencyId

        if (group != other.group) return false
        if (name != other.name) return false
        if (version != other.version) return false
        for ((key, value) in attributes) {
            if (key.makesUnique && other.attribute(key) != value) return false
        }
        for ((key, value) in other.attributes) {
            if (key.makesUnique && attribute(key) != value) return false
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

data class DependencyAttribute(val name: String, val makesUnique: Boolean, val defaultValue:String? = null) : MachineWritable {
    override fun toString(): String = name

    override fun writeMachine(json: Json) {
        json.writeValue(name as Any, String::class.java)
    }
}

/** Represents exclusion rule. All fields must match precisely to be considered for exclusion.
 * [group], [name] and [version] may contain "*" to signify wildcard that matches all.
 * Such wildcard in attribute means presence of that attribute, with any value. */
data class DependencyExclusion(val group: String, val name: String, val version: String, val attributes: Map<DependencyAttribute, String> = emptyMap()) : MachineWritable {

    private fun matches(pattern: String, value: String?): Boolean {
        return (pattern == "*" && value != null) || pattern == value
    }

    fun excludes(dependencyId: DependencyId): Boolean {
        return matches(group, dependencyId.group)
                && matches(name, dependencyId.name)
                && matches(version, dependencyId.version)
                && attributes.all { (key, value) -> matches(value, dependencyId.attributes[key]) }
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
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.M2OptionalAttribute to "true"
        )),
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.M2ScopeAttribute to "provided"
        )),
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.M2ScopeAttribute to "test"
        )),
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.M2ScopeAttribute to "system"
        ))
)

/** Represents dependency on a [dependencyId], with transitive dependencies, which may be excluded by [exclusions]. */
data class Dependency(val dependencyId: DependencyId, val exclusions: List<DependencyExclusion> = DefaultExclusions) : MachineWritable {
    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("id", dependencyId, DependencyId::class.java)
        json.writeValue("exclusions", exclusions, List::class.java)
        json.writeObjectEnd()
    }
}

/**
 * @param printOut true if this key should be included in print outs, such as machine writable or [toString()]
 */
@Suppress("unused")// T is used only in syntax
class ArtifactKey<T>(val name: String, val printOut:Boolean) {
    companion object {
        val ArtifactFile = ArtifactKey<Path>("artifactFile", true)
        val ArtifactData = ArtifactKey<ByteArray>("artifactData", false)
    }

    override fun toString(): String = name
}

/**
 * Data retrieved by resolving a dependency
 */
data class ResolvedDependency(val id: DependencyId,
                              val dependencies: List<Dependency>,
                              val resolvedFrom: Repository?,
                              val hasError: Boolean,
                              val log: CharSequence = "")
    : TinyMap<ArtifactKey<*>, Any>(), MachineWritable {

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("id", id)
        json.writeValue("dependencies", dependencies)
        json.writeValue("resolvedFrom", resolvedFrom)
        json.writeValue("hasError", hasError, Boolean::class.java)
        json.writeValue("log", log.toString(), String::class.java)
        json.writeArrayStart("data")
        forEachEntry { key, value ->
            json.writeObjectStart()
            json.writeValue("name", key.name)
            if (key.printOut) {
                json.writeValue("value", value)
            }
            json.writeObjectEnd()
        }
        json.writeArrayEnd()
        json.writeObjectEnd()
    }

    var artifact: Path?
        get() = this.getKey(ArtifactKey.ArtifactFile)
        set(value) {
            if (value == null) {
                this.removeKey(wemi.dependency.ArtifactKey.ArtifactFile)
            } else {
                this.putKey(wemi.dependency.ArtifactKey.ArtifactFile, value)
            }
        }

    var artifactData: ByteArray?
        get() {
            val data = this.getKey(wemi.dependency.ArtifactKey.ArtifactData)
            if (data != null) {
                return data
            }

            val artifact = this.artifact
            if (artifact != null) {
                try {
                    val bytes = artifact.readBytes()
                    this.putKey(wemi.dependency.ArtifactKey.ArtifactData, bytes)
                    return bytes
                } catch (e:Exception) {
                    LOG.warn("Failed to load artifactData of {}", this, e)
                }
            }

            return null
        }
        set(value) {
            if (value == null) {
                this.removeKey(wemi.dependency.ArtifactKey.ArtifactData)
            } else {
                this.putKey(wemi.dependency.ArtifactKey.ArtifactData, value)
            }
        }


    fun <T>getKey(key: ArtifactKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return super.get(key) as T?
    }

    fun <T>putKey(key: ArtifactKey<T>, value: T): T? {
        @Suppress("UNCHECKED_CAST")
        return super.put(key, value as Any) as T?
    }

    fun <T>removeKey(key: ArtifactKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return super.remove(key) as T?
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as ResolvedDependency

        if (id != other.id) return false
        if (dependencies != other.dependencies) return false
        if (resolvedFrom != other.resolvedFrom) return false
        if (hasError != other.hasError) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + dependencies.hashCode()
        result = 31 * result + (resolvedFrom?.hashCode() ?: 0)
        result = 31 * result + hasError.hashCode()
        return result
    }

    override fun toString(): String {
        return "ResolvedDependency(id=$id, data=${super.filteredToString { k, _ -> k.printOut} }, dependencies=$dependencies, resolvedFrom=$resolvedFrom, hasError=$hasError, log=$log)"
    }


    private companion object {
        private val LOG = LoggerFactory.getLogger(ResolvedDependency::class.java)
    }
}