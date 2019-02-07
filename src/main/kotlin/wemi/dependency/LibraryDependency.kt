package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonException
import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.WemiException
import wemi.boot.CLI
import wemi.collections.ArrayMap
import wemi.publish.InfoNode
import wemi.util.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

// This file contains resolver agnostic Repository/Dependency API

/** Represents repository from which artifacts may be retrieved */
@Json(Repository.Serializer::class)
abstract class Repository(val name: String) {

    /** Local repositories are preferred, because retrieving from them is faster. */
    abstract val local: Boolean

    /** Repository acting as a cache for this repository. Always searched first.
     * Must be [local]. Resolved dependencies will be stored here. */
    abstract val cache: Repository?

    /**
     * Attempt to resolve given dependency in this repository.
     *
     * @param dependency to resolve
     * @param repositories of repositories which may be queried for looking up dependencies
     */
    internal abstract fun resolveInRepository(dependency: DependencyId, repositories: Collection<Repository>): ResolvedDependency

    /** @return directory to lock on while resolving from this repository. It will be created if it doesn't exist. */
    internal abstract fun directoryToLock(): Path?

    /**
     * Publish [artifacts]s to this repository, under given [metadata]
     * and return the general location to where it was published.
     *
     * @param artifacts list of artifacts and their classifiers if any
     */
    internal abstract fun publish(metadata: InfoNode, artifacts: List<Pair<Path, String?>>):URI

    override fun toString(): String {
        return "Repository: $name"
    }

    internal class Serializer : wemi.util.JsonSerializer<Repository> {
        override fun JsonWriter.write(value: Repository) {
            val id = REPOSITORY_CLASSES[value.javaClass] ?: throw WemiException("Repository class ${value.javaClass} is not registered")
            writeObject {
                field("type", id)
                REPOSITORY_SERIALIZERS.getValue(id).writeTo(this, value)
            }
        }

        override fun read(value: JsonValue): Repository {
            val type = value.getString("type") ?: throw JsonException("Missing \"type\" field: $value")
            val serializer = REPOSITORY_SERIALIZERS[type] ?: throw WemiException("Repository of ID $type is not registered")
            return serializer.read(value)
        }
    }
}

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

    val isSnapshot:Boolean
        get() = version.endsWith("-SNAPSHOT")

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
 * @see MavenRepository.Type
 * @see MavenRepository.Scope
 * @see MavenRepository.Classifier
 * @see MavenRepository.Optional
 */
@Json(DependencyAttribute.Serializer::class)
data class DependencyAttribute internal constructor(val name: String, val makesUnique: Boolean, val defaultValue: String? = null) {

    init {
        KNOWN_DEPENDENCY_ATTRIBUTES[name] = this
    }

    override fun toString(): String = name

    internal class Serializer : JsonSerializer<DependencyAttribute> {

        override fun JsonWriter.write(value: DependencyAttribute) {
            writeValue(value.name, String::class.java)
        }

        override fun read(value: JsonValue): DependencyAttribute {
            val name = value.to(String::class.java)
            return KNOWN_DEPENDENCY_ATTRIBUTES[name] ?: throw JsonException("Unknown DependencyAttribute: $name")
        }
    }
}

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
 * Filters those that are what Maven considers optional.
 */
val DefaultExclusions = listOf(
        DependencyExclusion("*", "*", "*", mapOf(
                MavenRepository.Optional to "true"
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
            return if (exclusionsJson == null) {
                Dependency(id)
            } else {
                Dependency(id, exclusionsJson.toCollection(DependencyExclusion::class.java, ArrayList()))
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
class ArtifactKey<T>(val name: String, internal val printOut: Boolean) {
    override fun toString(): String = name
}

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
    : JsonWritable {

    private val artifacts = ArrayMap<ArtifactKey<*>, Any>()

    /**
     * Path to the artifact. Convenience accessor.
     *
     * @see getKey with [ArtifactFile]
     */
    var artifact: Path?
        get() = this.getKey(ArtifactFile)
        set(value) {
            if (value == null) {
                this.removeKey(ArtifactFile)
            } else {
                this.putKey(ArtifactFile, value)
            }
        }

    /**
     * Data of the artifact. Convenience accessor.
     *
     * When artifact data is not set, but [ArtifactFile] is, it will attempt to load it
     * and store the result under [ArtifactData].
     *
     * @see getKey with [ArtifactData]
     */
    var artifactData: ByteArray?
        get() {
            val data = this.getKey(ArtifactData)
            if (data != null) {
                return data
            }

            val artifact = this.artifact
            if (artifact != null) {
                try {
                    val bytes = Files.readAllBytes(artifact)
                    this.putKey(ArtifactData, bytes)
                    return bytes
                } catch (e: Exception) {
                    LOG.warn("Failed to load artifactData of {}", this, e)
                }
            }

            return null
        }
        set(value) {
            if (value == null) {
                this.removeKey(ArtifactData)
            } else {
                this.putKey(ArtifactData, value)
            }
        }

    /**
     * Retrieve the data under given [key].
     *
     * @return bound data or null if no data bound to that key
     */
    fun <T> getKey(key: ArtifactKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return artifacts[key] as T?
    }

    /**
     * Bind [value] to the [key].
     *
     * @return previously bound data or null if first binding
     */
    fun <T> putKey(key: ArtifactKey<T>, value: T): T? {
        @Suppress("UNCHECKED_CAST")
        return artifacts.put(key, value as Any) as T?
    }

    /**
     * Remove the binding of [key] to bound value, if any.
     *
     * @return previously bound data or null
     */
    fun <T> removeKey(key: ArtifactKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return artifacts.remove(key) as T?
    }

    override fun JsonWriter.write() {
        writeObject {
            field("id", id)
            fieldCollection("dependencies", dependencies)
            field("resolvedFrom", resolvedFrom)
            field("hasError", hasError)
            field("log", log.toString())
            name("data").writeArray {
                artifacts.forEachEntry { key, value ->
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
        return "ResolvedDependency(id=$id, data=${artifacts.filteredToString { k, _ -> k.printOut }}, dependencies=$dependencies, resolvedFrom=$resolvedFrom, hasError=$hasError, log=$log)"
    }


    private companion object {
        private val LOG = LoggerFactory.getLogger(ResolvedDependency::class.java)
    }
}

private val KNOWN_DEPENDENCY_ATTRIBUTES:MutableMap<String, DependencyAttribute> = Collections.synchronizedMap(HashMap())
private val REPOSITORY_SERIALIZERS:MutableMap<String, JsonSerializer<Repository>> = HashMap()
private val REPOSITORY_CLASSES:MutableMap<Class<Repository>, String> = HashMap()

private val init:Unit = Unit.apply {
    // Make sure that attributes in there are registered
    registerResolver("M2", MavenRepository.Serializer)
    MavenRepository
}

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun <T:Repository> registerResolver(id:String, serializer:JsonSerializer<T>, type:Class<T>) {
    REPOSITORY_SERIALIZERS[id] = serializer as JsonSerializer<Repository>
    REPOSITORY_CLASSES[type as Class<Repository>] = id
}

/** Register given [Repository] under given [id] with given [serializer].
 *
 * [serializer] will be invoked in writer which is currently writing an object.
 * Any property name is available, except for `"type"`, which already holds [id]. */
inline fun <reified T:Repository> registerResolver(id:String, serializer:JsonSerializer<T>) {
    registerResolver(id, serializer, T::class.java)
}

/**
 * Returns a pretty-printed string in which the system is displayed as a tree of dependencies.
 * Uses full range of unicode characters for clarity.
 */
fun Map<DependencyId, ResolvedDependency>.prettyPrint(explicitRoots: Collection<DependencyId>?): CharSequence {
    /*
    ╤ org.foo:proj:1.0 ✅
    │ ╘ com.bar:pr:2.0 ❌⛔️
    ╞ org.foo:proj:1.0 ✅⤴
    ╘ com.baz:pr:2.0 ❌⛔️

    Status symbols:
    OK ✅
    Error ❌⛔️
    Missing ❓
    Already shown ⤴
     */
    val STATUS_NORMAL: Byte = 0
    val STATUS_NOT_RESOLVED: Byte = 1
    val STATUS_CYCLIC: Byte = 2

    class NodeData(val dependencyId: DependencyId, var status: Byte)

    val nodes = HashMap<DependencyId, TreeNode<NodeData>>()

    // Build nodes
    for (depId in keys) {
        nodes[depId] = TreeNode(NodeData(depId, STATUS_NORMAL))
    }

    // Connect nodes (even with cycles)
    val notResolvedNodes = ArrayList<TreeNode<NodeData>>()// ConcurrentModification workaround
    nodes.forEach { depId, node ->
        this@prettyPrint[depId]?.dependencies?.forEach { dep ->
            var nodeToConnect = nodes[dep.dependencyId]
            if (nodeToConnect == null) {
                nodeToConnect = TreeNode(NodeData(dep.dependencyId, STATUS_NOT_RESOLVED))
                notResolvedNodes.add(nodeToConnect)
            }
            node.add(nodeToConnect)
        }
    }
    for (notResolvedNode in notResolvedNodes) {
        nodes[notResolvedNode.value.dependencyId] = notResolvedNode
    }

    val remainingNodes = HashMap(nodes)

    fun liftNode(dependencyId: DependencyId): TreeNode<NodeData> {
        // Lift what was asked
        val liftedNode = remainingNodes.remove(dependencyId) ?: return TreeNode(NodeData(dependencyId, STATUS_CYCLIC))
        val resultNode = TreeNode(liftedNode.value)
        // Lift all dependencies too and return them in the result node
        for (dependencyNode in liftedNode) {
            resultNode.add(liftNode(dependencyNode.value.dependencyId))
        }
        return resultNode
    }

    val roots = ArrayList<TreeNode<NodeData>>()

    // Lift explicit roots
    explicitRoots?.forEach { root ->
        val liftedNode = liftNode(root)
        // Check for nodes that are in explicitRoots but were never resolved to begin with
        if (liftedNode.value.status == STATUS_CYCLIC && !this.containsKey(liftedNode.value.dependencyId)) {
            liftedNode.value.status = STATUS_NOT_RESOLVED
        }
        roots.add(liftedNode)
    }

    // Lift implicit roots
    for (key in this.keys) {
        if (remainingNodes.containsKey(key)) {
            roots.add(liftNode(key))
        }
    }

    // Lift rest as roots
    while (remainingNodes.isNotEmpty()) { //This should never happen?
        val (dependencyId, _) = remainingNodes.iterator().next()
        roots.add(liftNode(dependencyId))
    }

    // Now we can start printing!

    return printTree(roots) { result ->
        val dependencyId = this.dependencyId

        result.format(format = Format.Bold)
                .append(dependencyId.group).append(':')
                .append(dependencyId.name).append(':')
                .append(dependencyId.version).format()

        for ((key, value) in dependencyId.attributes) {
            result.append(' ').append(key.name).append('=').append(value)
        }
        result.append(' ')

        val resolved = this@prettyPrint[dependencyId]

        when {
            resolved == null -> result.append(CLI.ICON_UNKNOWN)
            resolved.hasError -> result.append(CLI.ICON_FAILURE)
            else -> result.append(CLI.ICON_SUCCESS)
        }

        if (status == STATUS_CYCLIC) {
            result.append(CLI.ICON_SEE_ABOVE)
        } else {
            val resolvedFrom = resolved?.resolvedFrom
            if (resolvedFrom != null) {
                result.format(Color.White).append(" from ").format().append(resolvedFrom)
            }
        }
    }
}