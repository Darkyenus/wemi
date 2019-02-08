package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.util.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * Artifact classifier.
 * @see DependencyId.classifier
 */
typealias Classifier = String

/** Concatenate two classifiers. */
fun joinClassifiers(first:Classifier, second:Classifier):Classifier {
    return when {
        first.isEmpty() -> second
        second.isEmpty() -> first
        else -> "$first-$second"
    }
}

/** No classifier (empty string) */
const val NoClassifier: Classifier = ""
/** Classifier appended to artifacts with sources */
const val SourcesClassifier: Classifier = "sources"
/** Classifier appended to artifacts with Javadoc */
const val JavadocClassifier: Classifier = "javadoc"

internal const val DEFAULT_TYPE:String = "jar"
internal const val DEFAULT_SCOPE:String = "compile"
internal const val DEFAULT_OPTIONAL:Boolean = false

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

                        /**
                         * Various variants of the same dependency.
                         * Examples: jdk15, sources, javadoc, linux
                         * @see SourcesClassifier
                         * @see JavadocClassifier
                         */
                        val classifier:Classifier = NoClassifier,
                        /**
                         * Corresponds to the packaging of the dependency (and overrides it).
                         * Determines what sort of artifact is retrieved.
                         *
                         * Examples: jar (default), pom (returns pom.xml, used internally)
                         */
                        val type:String = DEFAULT_TYPE,
                        /**
                         * Scope of the dependency.
                         *
                         * Examples: compile, provided, test
                         * See https://maven.apache.org/pom.html#Dependencies
                         *
                         * NOTE: In Wemi used only when filtering, does not actually modify when is the dependency used!
                         */
                        val scope:String = DEFAULT_SCOPE,
                        /** Optional transitive dependencies are skipped by default by Wemi. */
                        val optional:Boolean = DEFAULT_OPTIONAL) {

    /** Snapshot [version]s end with `-SNAPSHOT` suffix. */
    val isSnapshot:Boolean
        get() = version.endsWith("-SNAPSHOT")


    override fun toString(): String {
        val result = StringBuilder()
        result.append(group).append(':').append(name).append(':').append(version)
        if (preferredRepository != null) {
            result.append('@').append(preferredRepository)
        }
        if (classifier != NoClassifier) {
            result.append(" classifier:").append(classifier)
        }
        if (type != DEFAULT_TYPE) {
            result.append(" type:").append(type)
        }
        if (scope != DEFAULT_SCOPE) {
            result.append(" scope:").append(scope)
        }
        if (optional != DEFAULT_OPTIONAL) {
            result.append(" optional")
        }
        return result.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DependencyId

        if (group != other.group) return false
        if (name != other.name) return false
        if (version != other.version) return false
        if (classifier != other.classifier) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int {
        var result = group.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + classifier.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }

    internal class Serializer : JsonSerializer<DependencyId> {

        override fun JsonWriter.write(value: DependencyId) {
            writeObject {
                field("group", value.group)
                field("name", value.name)
                field("version", value.version)

                field("preferredRepository", value.preferredRepository)

                if (value.classifier != NoClassifier) {
                    field("classifier", value.classifier)
                }
                if (value.type != DEFAULT_TYPE) {
                    field("type", value.type)
                }
                if (value.scope != DEFAULT_SCOPE) {
                    field("scope", value.scope)
                }
                if (value.optional != DEFAULT_OPTIONAL) {
                    field("optional", value.optional)
                }
            }
        }

        override fun read(value: JsonValue): DependencyId {
            return DependencyId(
                    value.field("group"),
                    value.field("name"),
                    value.field("version"),

                    value.field("preferredRepository"),
                    value.field("classifier", NoClassifier),
                    value.field("type", DEFAULT_TYPE),
                    value.field("scope", DEFAULT_SCOPE),
                    value.field("optional", DEFAULT_OPTIONAL)
            )
        }
    }
}

/** Represents exclusion rule. All non-null fields must match precisely to be considered for exclusion. */
@Json(DependencyExclusion.Serializer::class)
data class DependencyExclusion(val group: String? = null, val name: String? = null, val version: String? = null,
                               val classifier:Classifier? = null, val type:String? = null, val scope:String? = null, val optional:Boolean? = null) {

    private fun <T> matches(pattern: T?, value: T): Boolean {
        return (pattern ?: return true) == value
    }

    /**
     * Checks if given [dependencyId] is excluded by this rule.
     * @return true when all non-null properties are equal to the values in [dependencyId]
     */
    fun excludes(dependencyId: DependencyId): Boolean {
        return matches(group, dependencyId.group)
                && matches(name, dependencyId.name)
                && matches(version, dependencyId.version)
                && matches(classifier, dependencyId.classifier)
                && matches(type, dependencyId.type)
                && matches(scope, dependencyId.scope)
                && matches(optional, dependencyId.optional)
    }

    override fun toString(): String {
        return "${group ?: "*"}:${name ?: "*"}:${version ?: "*"} classifier:${classifier ?: "*"} type:${type
                ?: "*"} scope:${scope ?: "*"} optional:${optional ?: "*"}"
    }

    internal class Serializer : JsonSerializer<DependencyExclusion> {
        override fun JsonWriter.write(value: DependencyExclusion) {
            writeObject {
                field("group", value.group)
                field("name", value.name)
                field("version", value.version)

                field("classifier", value.classifier)
                field("type", value.type)
                field("scope", value.scope)
                field("optional", value.optional)
            }
        }

        override fun read(value: JsonValue): DependencyExclusion {
            return DependencyExclusion(
                    value.field("group"),
                    value.field("name"),
                    value.field("version"),

                    value.field("classifier"),
                    value.field("type"),
                    value.field("scope"),
                    value.field("optional")
            )
        }
    }
}

/**
 * Exclusions used by default by [Dependency].
 *
 * Filters those that are what Maven considers optional.
 */
val DefaultExclusions = listOf(DependencyExclusion(optional = true))

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

private val RESOLVED_DEPENDENCY_LOG = LoggerFactory.getLogger(ResolvedDependency::class.java)

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
                                     val log: CharSequence = "") : JsonWritable {

    /** If the artifact has been resolved to a file in a local filesystem,
     * the path to such file will be stored under this key.
     *
     * @see artifactData will contain the data in this file */
    var artifact: Path? = null

    /**
     * Data of the artifact. Convenience accessor.
     * If the data of the artifact has been resolved, it will be stored under this key.
     *
     * When null, but [artifact] is set and exists, getter will attempt to load it
     * and store the result here.
     */
    var artifactData: ByteArray? = null
        get() {
            val data = field
            if (data != null) {
                return data
            }

            val artifact = this.artifact
            if (artifact != null) {
                try {
                    val bytes = Files.readAllBytes(artifact)
                    field = bytes
                    return bytes
                } catch (e: Exception) {
                    RESOLVED_DEPENDENCY_LOG.warn("Failed to load artifactData of {}", this, e)
                }
            }

            return null
        }

    internal var pomFile:Path? = null
    internal var pomData:ByteArray? = null
    internal var pom:Pom? = null
    internal var rawPom:RawPom? = null
    internal var pomUrl: URL? = null

    override fun JsonWriter.write() {
        writeObject {
            field("id", id)
            fieldCollection("dependencies", dependencies)
            field("resolvedFrom", resolvedFrom)
            field("hasError", hasError)
            field("log", log.toString())
            if (artifact != null) {
                field("artifact", artifact)
            }
            if (pomFile != null) {
                field("pomFile", pomFile)
            }
            if (pomUrl != null) {
                field("pomUrl", pomUrl)
            }
        }
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append("ResolvedDependency(id=").append(id)
        result.append(", dependencies=").append(dependencies)
        result.append(", resolvedFrom=").append(resolvedFrom)
        result.append(", hasError=").append(hasError)
        if (log.isNotEmpty()) {
            result.append(", log=").append(log)
        }
        if (artifact != null) {
            result.append(", artifact=").append(artifact)
        }
        if (pomFile != null) {
            result.append(", pomFile=").append(pomFile)
        }
        if (pomUrl != null) {
            result.append(", pomUrl=").append(pomUrl)
        }
        return result.append(')').toString()
    }
}