@file:Suppress("unused")

package wemi

import wemi.dependency.*
import wemi.util.WithDescriptiveString
import java.io.File
import java.net.URL

/** Version of Wemi build system */
val WemiVersion = "0.0"
/** Version of Kotlin used for build scripts */
val WemiKotlinVersion = "1.1.3-2"

/** Immutable view into the list of loaded projects. */
val AllProjects:Map<String, Project>
    get() = BuildScriptData.AllProjects

/** Immutable view into the list of loaded keys. */
val AllKeys:Map<String, Key<*>>
    get() = BuildScriptData.AllKeys

/** Immutable view into the list of loaded keys. */
val AllConfigurations:Map<String, Configuration>
    get() = BuildScriptData.AllConfigurations

/** wemi.Key which can have value of type [Value] assigned, through [Project] or [Configuration]. */
class Key<out Value> internal constructor(val name:String,
                                          val description:String,
                                          /** True if defaultValue is set, false if not.
                                           * Needed, because we don't know whether or not is [Value] nullable
                                           * or not, so we need to know if we should return null or not. */
                                          internal val hasDefaultValue:Boolean,
                                          internal val defaultValue:Value?) : WithDescriptiveString {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as Key<*>

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String {
        return name
    }

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} - $description"
}

class Configuration internal constructor(override val name: String,
                    val description: String,
                    parent: ConfigurationHolder?) : ConfigurationHolder(parent), WithDescriptiveString {
    override fun toString(): String {
        return name
    }

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} - \"$description\""
}

class Project internal constructor(override val name: String, val projectRoot: File) : ConfigurationHolder(null), WithDescriptiveString {
    override fun toString(): String = name

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} at $projectRoot"
}

fun project(projectRoot: File, initializer: Project.() -> Unit): ProjectDelegate {
    return ProjectDelegate(projectRoot, initializer)
}

fun <Value>key(description: String, defaultValue: Value, cached:Boolean = false): KeyDelegate<Value> {
    return KeyDelegate(description, true, defaultValue, cached)
}

fun <Value>key(description: String, cached: Boolean = false): KeyDelegate<Value> {
    return KeyDelegate(description, false, null, cached)
}

fun configuration(description: String, parent: ConfigurationHolder?): ConfigurationDelegate {
    return ConfigurationDelegate(description, parent)
}

/** Convenience ProjectDependency creator. */
fun dependency(group:String, name:String, version:String, preferredRepository: Repository?, vararg attributes:Pair<ProjectAttribute, String>): ProjectDependency {
    return ProjectDependency(ProjectId(group, name, version, preferredRepository, attributes = mapOf(*attributes)))
}

/** Convenience ProjectDependency creator.
 * @param groupNameVersion Gradle-like semicolon separated group, name and version of the dependency.
 *          If the amount of ':'s isn't exactly 2, or one of the triplet is empty, runtime exception is thrown. */
fun dependency(groupNameVersion:String, preferredRepository: Repository?, vararg attributes:Pair<ProjectAttribute, String>): ProjectDependency {
    val first = groupNameVersion.indexOf(':')
    val second = groupNameVersion.indexOf(':', startIndex = maxOf(first + 1, 0))
    val third = groupNameVersion.indexOf(':', startIndex = maxOf(second + 1, 0))
    if (first < 0 || second < 0 || third >= 0) {
        throw WemiException("groupNameVersion must contain exactly two semicolons: '$groupNameVersion'")
    }
    if (first == 0 || second <= first + 1 || second + 1 == groupNameVersion.length) {
        throw WemiException("groupNameVersion must not have empty elements: '$groupNameVersion'")
    }

    val group = groupNameVersion.substring(0, first)
    val name = groupNameVersion.substring(first + 1, second)
    val version = groupNameVersion.substring(second + 1)

    return dependency(group, name, version, preferredRepository, *attributes)
}

fun ConfigurationHolder.kotlinDependency(name: String):ProjectDependency {
    return ProjectDependency(ProjectId("org.jetbrains.kotlin", "kotlin-"+name, Keys.kotlinVersion.get()))
}

fun repository(name: String, url:String, checksum: Repository.M2.Checksum):Repository.M2 {
    val usedUrl = URL(url)
    return Repository.M2(name,
            usedUrl,
            if (usedUrl.protocol.equals("file", ignoreCase = true)) null else LocalM2Repository,
            checksum)
}