@file:Suppress("unused")

import java.io.File

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

/** Key which can have value of type [Value] assigned, through [Project] or [Configuration]. */
class Key<out Value> internal constructor(val name:String,
                                          val description:String,
                                          /** True if defaultValue is set, false if not.
                                           * Needed, because we don't know whether or not is [Value] nullable
                                           * or not, so we need to know if we should return null or not. */
                                          internal val hasDefaultValue:Boolean,
                                          internal val defaultValue:Value?) {

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
}

class Configuration internal constructor(override val name: String,
                    val description: String,
                    parent: ConfigurationHolder?) : ConfigurationHolder(parent)

class Project internal constructor(override val name: String, val projectRoot: File) : ConfigurationHolder(null)

fun <This>project(projectRoot: File = File("."), initializer: Project.() -> Unit): ProjectDelegate<This> {
    return ProjectDelegate(projectRoot, initializer)
}

fun <This, Value>key(description: String, defaultValue: Value): KeyDelegate<This, Value> {
    return KeyDelegate(description, true, defaultValue)
}

fun <This, Value>key(description: String): KeyDelegate<This, Value> {
    return KeyDelegate(description, false, null)
}

fun <This>configuration(description: String, parent: ConfigurationHolder? = null): ConfigurationDelegate<This> {
    return ConfigurationDelegate(description, parent)
}
