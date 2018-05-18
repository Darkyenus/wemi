package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonException
import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.collections.TinyMap
import wemi.dependency.ArtifactKey.Companion.ArtifactData
import wemi.dependency.ArtifactKey.Companion.ArtifactFile
import wemi.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * Artifact classifier.
 *
 * @see Repository.M2.Classifier for where it is used.
 */
typealias Classifier = String

/**
 * Unique identifier for project/module to be resolved.
 * May have dependencies on other projects and may have artifacts.
 *
 * @param group of project (aka organisation)
 * @param name of project
 * @param version of project (aka revision)
 *
 * @param preferredRepository repository in which to search for this project first
 *          (its cache, if any, is searched even earlier)
 */
@Json(DependencyId.Serializer::class)
data class DependencyId(val group: String,
                        val name: String,
                        val version: String,

                        val preferredRepository: Repository? = null,
                        val attributes: Map<DependencyAttribute, String> = emptyMap()) {

    fun attribute(attribute: DependencyAttribute): String? {
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

        return true
    }

    override fun hashCode(): Int {
        var result = group.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        // Can't use attributes when computing hashCode,
        // because their default values are considered as well and may change equality.
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


    internal class Serializer : JsonSerializer<DependencyId> {

        override fun JsonWriter.write(value: DependencyId) {
            writeObject {
                field("group", value.group)
                field("name", value.name)
                field("version", value.version)

                field("preferredRepository", value.preferredRepository)
                fieldMap("attributes", value.attributes)
            }
        }

        override fun read(value: JsonValue): DependencyId {
            return DependencyId(
                    value.field("group"),
                    value.field("name"),
                    value.field("version"),

                    value.field("preferredRepository"),
                    value.fieldToMap("attributes", HashMap())
                    )
        }
    }
}

/**
 * Instance of this class uniquely defines a dependency attribute for [DependencyId.attributes].
 * Values bound to [DependencyAttribute] keys are [String]s.
 *
 * When creating new attributes, make sure that they are loaded earlier than any of their deserialization.
 *
 * @param name of this attribute
 * @param makesUnique if two equal [DependencyId], differing only in value/presence of this attribute, are equal or not
 *                      i.e. if [DependencyId]s differ in this attribute and makesUnique is true, they are not considered equal, otherwise they are
 * @param defaultValue when [DependencyId] does not have this attribute set explicitly, treat it as having this value, if not null
 *
 * @see Repository.M2.Type
 * @see Repository.M2.Scope
 * @see Repository.M2.Classifier
 * @see Repository.M2.Optional
 */
@Json(DependencyAttribute.Serializer::class)
data class DependencyAttribute internal constructor(val name: String, val makesUnique: Boolean, val defaultValue: String? = null) {

    init {
        KNOWN_DEPENDENCY_ATTRIBUTES[name] = this
    }

    override fun toString(): String = name

    internal class Serializer : JsonSerializer<DependencyAttribute> {

        init {
            // Make sure that attributes in there are registered
            Repository.M2
        }

        override fun JsonWriter.write(value: DependencyAttribute) {
            writeValue(value.name, String::class.java)
        }

        override fun read(value: JsonValue): DependencyAttribute {
            val name = value.to(String::class.java)
            return KNOWN_DEPENDENCY_ATTRIBUTES[name] ?: throw JsonException("Unknown DependencyAttribute: $name")
        }
    }
}

/**
 * Registered dependency attributes, for deserialization.
 */
private val KNOWN_DEPENDENCY_ATTRIBUTES:MutableMap<String, DependencyAttribute> = Collections.synchronizedMap(HashMap())

/** Represents exclusion rule. All fields must match precisely to be considered for exclusion.
 * [group], [name] and [version] may contain "*" to signify wildcard that matches all.
 * Such wildcard in attribute means presence of that attribute, with any value. */
@Json(DependencyExclusion.Serializer::class)
data class DependencyExclusion(val group: String, val name: String, val version: String, val attributes: Map<DependencyAttribute, String> = emptyMap()) {

    private fun matches(pattern: String, value: String?): Boolean {
        return (pattern == "*" && value != null) || pattern == value
    }

    /**
     * Checks if given [dependencyId] is excluded by this rule.
     *
     * @return true when [group], [name], [version] and all present [attributes] have a value
     *         that matches those in [dependencyId].
     */
    fun excludes(dependencyId: DependencyId): Boolean {
        return matches(group, dependencyId.group)
                && matches(name, dependencyId.name)
                && matches(version, dependencyId.version)
                && attributes.all { (key, value) -> matches(value, dependencyId.attributes[key]) }
    }

    override fun toString(): String {
        return "$group:$name:$version $attributes"
    }

    internal class Serializer : JsonSerializer<DependencyExclusion> {
        override fun JsonWriter.write(value: DependencyExclusion) {
            writeObject {
                field("group", value.group)
                field("name", value.name)
                field("version", value.version)

                fieldMap("attributes", value.attributes)
            }
        }

        override fun read(value: JsonValue): DependencyExclusion {
            return DependencyExclusion(
                    value.field("group"),
                    value.field("name"),
                    value.field("version"),

                    value.fieldToMap("attributes", HashMap())
            )
        }
    }
}

/**
 * Exclusions used by default by [Dependency].
 *
 * Filters those that are what Maven considers optional, or in provided, test or system scope.
 */
val DefaultExclusions = listOf(
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.Optional to "true"
        )),
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.Scope to "provided"
        )),
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.Scope to "test"
        )),
        DependencyExclusion("*", "*", "*", mapOf(
                Repository.M2.Scope to "system"
        ))
)

/** Represents dependency on a [dependencyId], with transitive dependencies, which may be excluded by [exclusions].
 *
 * @param exclusions to filter transitive dependencies with. [DefaultExclusions] by default. */
@Json(Dependency.Serializer::class)
data class Dependency(val dependencyId: DependencyId, val exclusions: List<DependencyExclusion> = DefaultExclusions) : WithDescriptiveString {

    override fun toDescriptiveAnsiString(): String {
        return if (exclusions === DefaultExclusions) {
            dependencyId.toString()
        } else {
            "$dependencyId, exclusions= $exclusions"
        }
    }

    internal class Serializer : JsonSerializer<Dependency> {
        override fun JsonWriter.write(value: Dependency) {
            writeObject {
                field("id", value.dependencyId)
                if (value.exclusions !== DefaultExclusions) {
                    fieldCollection("exclusions", value.exclusions)
                }
            }
        }

        override fun read(value: JsonValue): Dependency {
            val id = value.field<DependencyId>("id")
            val exclusionsJson = value.get("exclusions")
            if (exclusionsJson == null) {
                return Dependency(id)
            } else {
                return Dependency(id, exclusionsJson.toCollection(DependencyExclusion::class.java, ArrayList()))
            }
        }
    }
}

/**
 * Instance serves as a key for artifacts resolved and stored in [ResolvedDependency].
 *
 * @param T the type of the value bound to this key
 * @param printOut true if this key should be included in print outs, such as machine writable or [toString()]
 *
 * @see ArtifactFile
 * @see ArtifactData
 */
@Suppress("unused")// T is used only in syntax
class ArtifactKey<T>(val name: String, val printOut: Boolean) {

    override fun toString(): String = name

    companion object {
        /**
         * If the artifact has been resolved to a file in a local filesystem,
         * the path to such file will be stored under this key.
         *
         * @see ArtifactData will contain the data in this file
         */
        val ArtifactFile = ArtifactKey<Path>("artifactFile", true)
        /**
         * If the data of the artifact has been resolved, it will be stored under this key.
         */
        val ArtifactData = ArtifactKey<ByteArray>("artifactData", false)
    }
}

/**
 * Data retrieved by resolving a dependency.
 *
 * If successful, contains information about transitive [dependencies] and holds artifacts that were found.
 *
 * @param id that was resolved
 * @param dependencies of the [id] that were found
 * @param resolvedFrom in which repository was [id] ultimately found in
 * @param hasError true if this dependency failed to resolve (partially or completely), for any reason
 * @param log may contain a message explaining why did the dependency failed to resolve
 */
class ResolvedDependency constructor(val id: DependencyId,
                              val dependencies: List<Dependency>,
                              val resolvedFrom: Repository?,
                              val hasError: Boolean,
                              val log: CharSequence = "")
    : TinyMap<ArtifactKey<*>, Any>(), JsonWritable {

    /**
     * Path to the artifact. Convenience accessor.
     *
     * @see getKey with [ArtifactKey.ArtifactFile]
     */
    var artifact: Path?
        get() = this.getKey(ArtifactKey.ArtifactFile)
        set(value) {
            if (value == null) {
                this.removeKey(wemi.dependency.ArtifactKey.ArtifactFile)
            } else {
                this.putKey(wemi.dependency.ArtifactKey.ArtifactFile, value)
            }
        }

    /**
     * Data of the artifact. Convenience accessor.
     *
     * When artifact data is not set, but [ArtifactKey.ArtifactFile] is, it will attempt to load it
     * and store the result under [ArtifactKey.ArtifactData].
     *
     * @see getKey with [ArtifactKey.ArtifactData]
     */
    var artifactData: ByteArray?
        get() {
            val data = this.getKey(wemi.dependency.ArtifactKey.ArtifactData)
            if (data != null) {
                return data
            }

            val artifact = this.artifact
            if (artifact != null) {
                try {
                    val bytes = Files.readAllBytes(artifact)
                    this.putKey(wemi.dependency.ArtifactKey.ArtifactData, bytes)
                    return bytes
                } catch (e: Exception) {
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

    /**
     * Retrieve the data under given [key].
     *
     * @return bound data or null if no data bound to that key
     */
    fun <T> getKey(key: ArtifactKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return super.get(key) as T?
    }

    /**
     * Bind [value] to the [key].
     *
     * @return previously bound data or null if first binding
     */
    fun <T> putKey(key: ArtifactKey<T>, value: T): T? {
        @Suppress("UNCHECKED_CAST")
        return super.put(key, value as Any) as T?
    }

    /**
     * Remove the binding of [key] to bound value, if any.
     *
     * @return previously bound data or null
     */
    fun <T> removeKey(key: ArtifactKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return super.remove(key) as T?
    }

    override fun JsonWriter.write() {
        writeObject {
            field("id", id)
            fieldCollection("dependencies", dependencies)
            field("resolvedFrom", resolvedFrom)
            field("hasError", hasError)
            field("log", log.toString())
            name("data").writeArray {
                forEachEntry { key, value ->
                    writeObject {
                        field("name", key.name)
                        if (key.printOut) {
                            name("value").writeValue(value, null)
                        }
                    }
                }
            }
        }
    }

    override fun toString(): String {
        return "ResolvedDependency(id=$id, data=${super.filteredToString { k, _ -> k.printOut }}, dependencies=$dependencies, resolvedFrom=$resolvedFrom, hasError=$hasError, log=$log)"
    }


    private companion object {
        private val LOG = LoggerFactory.getLogger(ResolvedDependency::class.java)
    }
}