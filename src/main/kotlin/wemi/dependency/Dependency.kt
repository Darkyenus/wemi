package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.util.*
import java.io.IOException
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
internal const val DEFAULT_SNAPSHOT_VERSION:String = ""

/**
 * Unique identifier for project/module to be resolved.
 * May have dependencies on other projects and may have artifacts.
 *
 * @param group of project (aka organisation)
 * @param name of project
 * @param version of project (aka revision)
 */
@Json(DependencyId.Serializer::class)
// TODO(jp): Make this into a regular class (without "data"), same with other classes
data class DependencyId(val group: String,
                        val name: String,
                        val version: String,

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
                        val type:String = DEFAULT_TYPE, // https://maven.apache.org/ref/3.6.0/maven-core/artifact-handlers.html
                        /**
                         * Scope of the dependency.
                         *
                         * Examples: compile, provided, test
                         * See https://maven.apache.org/pom.html#Dependencies
                         *
                         * TODO: Not correct soon
                         * NOTE: In Wemi used only when filtering, does not actually modify when is the dependency used!
                         */
                        @Deprecated("Moved to Dependency")
                        val scope:String = DEFAULT_SCOPE,
                        /** Optional transitive dependencies are skipped by default by Wemi. */
                        @Deprecated("Moved to Dependency")
                        val optional:Boolean = DEFAULT_OPTIONAL,
                        /** When [isSnapshot] and repository uses unique snapshots, `SNAPSHOT` in the [version]
                         * is replaced by this string for the last resolution pass. Resolver (Wemi) will automatically
                         * replace it with the newest snapshot version (in format: `timestamp-buildNumber`),
                         * but if you need a specific snapshot version, it can be set here. */
                        val snapshotVersion:String = DEFAULT_SNAPSHOT_VERSION) {

    /** Snapshot [version]s end with `-SNAPSHOT` suffix. */
    val isSnapshot:Boolean
        get() = version.endsWith("-SNAPSHOT")


    override fun toString(): String {
        val result = StringBuilder()
        result.append(group).append(':').append(name).append(':').append(version)
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
        if (snapshotVersion != DEFAULT_SNAPSHOT_VERSION) {
            result.append(" snapshotVersion:").append(snapshotVersion)
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
        if (snapshotVersion != other.snapshotVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = group.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + classifier.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + snapshotVersion.hashCode()
        return result
    }

    internal class Serializer : JsonSerializer<DependencyId> {

        override fun JsonWriter.write(value: DependencyId) {
            writeObject {
                field("group", value.group)
                field("name", value.name)
                field("version", value.version)

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
                if (value.snapshotVersion != DEFAULT_SNAPSHOT_VERSION) {
                    field("snapshotVersion", value.snapshotVersion)
                }
            }
        }

        override fun read(value: JsonValue): DependencyId {
            return DependencyId(
                    value.field("group"),
                    value.field("name"),
                    value.field("version"),

                    value.field("classifier", NoClassifier),
                    value.field("type", DEFAULT_TYPE),
                    value.field("scope", DEFAULT_SCOPE),
                    value.field("optional", DEFAULT_OPTIONAL),
                    value.field("snapshotVersion", DEFAULT_SNAPSHOT_VERSION)
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

/** Represents dependency on a [dependencyId], with transitive dependencies, which may be excluded by [exclusions].
 *
 * @param exclusions to filter transitive dependencies with */
@Json(Dependency.Serializer::class)
data class Dependency(val dependencyId: DependencyId,
                      /**
                       * Scope of the dependency.
                       *
                       * Examples: compile, provided, test
                       * See https://maven.apache.org/pom.html#Dependencies
                       */
                      val scope:String = DEFAULT_SCOPE,
                      /** Optional transitive dependencies are skipped by default by Wemi.
                       * See https://maven.apache.org/guides/introduction/introduction-to-optional-and-excludes-dependencies.html */
                      val optional:Boolean = DEFAULT_OPTIONAL,
                      /** Filtering applied to transitive dependencies to exclude some. */
                      val exclusions: List<DependencyExclusion> = emptyList(),
                      /** When transitive dependencies match any of these through "{groupId, artifactId, type, classifier}",
                       * they will be replaced with info in here.
                       *
                       * See https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management */
                      val dependencyManagement: List<Dependency> = emptyList()// TODO(jp): Wire in
) : WithDescriptiveString {

    override fun toDescriptiveAnsiString(): String {
        return if (exclusions.isEmpty()) {
            dependencyId.toString()
        } else {
            "$dependencyId, exclusions= $exclusions"
        }
    }

    internal class Serializer : JsonSerializer<Dependency> {
        override fun JsonWriter.write(value: Dependency) {
            writeObject {
                field("id", value.dependencyId)
                if (value.exclusions.isNotEmpty()) {
                    fieldCollection("exclusions", value.exclusions)
                }
            }
        }

        override fun read(value: JsonValue): Dependency {
            val id = value.field<DependencyId>("id")
            val exclusions:List<DependencyExclusion> = value.fieldToCollection("exclusions", ArrayList())

            return Dependency(id, exclusions =exclusions)
        }
    }
}

private val ARTIFACT_PATH_LOG = LoggerFactory.getLogger(ArtifactPath::class.java)

/** Represents a [path] with lazily loaded [data]. */
class ArtifactPath(val path:Path, data:ByteArray?,
                   /** From which repository was the artifact retrieved */
                   val repository:Repository,
                   /** Inside the repository from which the artifact was possibly originally obtained */
                   val url:URL,
                   /** Whether or not was this artifact retrieved from cache */
                   val fromCache:Boolean) {

    /** When null, but [path] exists, getter will attempt to load it and store the result for later queries. */
    var data: ByteArray? = data
        get() {
            if (field == null) {
                try {
                    field = Files.readAllBytes(path)
                } catch (e: IOException) {
                    ARTIFACT_PATH_LOG.warn("Failed to load data from '{}' which was supposed to be there", path, e)
                }
            }

            return field
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArtifactPath

        if (path != other.path) return false

        return true
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }

    override fun toString(): String {
        return path.toString()
    }
}

/**
 * Data retrieved by resolving a dependency.
 *
 * If successful, contains information about transitive [dependencies] and holds artifacts that were found.
 */
// TODO(jp): Convert this to two types - successful and unsuccessful version (???)
class ResolvedDependency private constructor(
        /** That was being resolved */
        val id: DependencyId,
        /** Of the [id] that were found */
        val dependencies: List<Dependency>,
        /** In which (non-cache) repository was [id] ultimately found in */
        val resolvedFrom: Repository?,
        /** May contain a message explaining why did the dependency failed to resolve, if [hasError] */
        val log: CharSequence?,
        /** If the artifact has been resolved to a file in a local filesystem, it is here. */
        val artifact:ArtifactPath?
) : JsonWritable {

    /** `true` if this dependency failed to resolve (partially or completely), for any reason */
    val hasError: Boolean
        get() = log != null

    /** Error constructor */
    constructor(id:DependencyId, log:CharSequence, resolvedFrom:Repository? = null)
            : this(id, emptyList(), resolvedFrom, log, null)

    /** Success constructor */
    constructor(id:DependencyId, dependencies:List<Dependency>, resolvedFrom:Repository, artifact:ArtifactPath)
            :this(id, dependencies, resolvedFrom, null, artifact)

    override fun JsonWriter.write() {
        writeObject {
            field("id", id)
            fieldCollection("dependencies", dependencies)
            field("resolvedFrom", resolvedFrom)
            field("hasError", hasError)
            if (!log.isNullOrBlank()) {
                field("log", log.toString())
            }
            if (artifact != null) {
                field("artifact", artifact.path)
            }
        }
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append("ResolvedDependency(id=").append(id)
        result.append(", dependencies=").append(dependencies)
        result.append(", resolvedFrom=").append(resolvedFrom)
        result.append(", hasError=").append(hasError)
        if (!log.isNullOrBlank()) {
            result.append(", log=").append(log)
        }
        if (artifact != null) {
            result.append(", artifact=").append(artifact.path)
        }
        return result.append(')').toString()
    }
}

// TODO(jp): Documentation
class WithStatus<Thing, Status>(val thing:Thing, val status:Status)

enum class TransitiveDependencyStatus {
    OK,
    BROKEN,
    SKIPPED
}
