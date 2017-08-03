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

typealias LazyKeyValue<Value> = Scope.() -> Value

class Configuration internal constructor(override val name: String,
                                         val description: String,
                                         parent: Configuration?) : BindingHolder(parent), WithDescriptiveString {

    override fun toString(): String {
        return name
    }

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} - \"$description\""
}

class Project internal constructor(override val name: String, val projectRoot: File) : BindingHolder(null), WithDescriptiveString, Scope {
    override fun scopeToString(): String = name + "/"

    override fun toString(): String = name

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} at $projectRoot"

    override val scopeBindingHolder: BindingHolder
        get() = this
    override val scopeParent: Scope?
        get() = null
}

interface Scope {

    val scopeBindingHolder: BindingHolder

    val scopeParent:Scope?

    //TODO val scopeCache:ScopeCache which will store other existing scope combinations and will cache resolved key values

    fun <Result> with(configuration: Configuration, action: Scope.() -> Result):Result {
        val scope = ConfigurationScope(configuration, this)
        return scope.action()
    }

    private inline fun <Value>unpack(key: Key<Value>, binding:LazyKeyValue<Value>, reverseAdditions:ArrayList<List<LazyKeyValue<Value>>>?):Value {
        //TODO caching

        var result = this.binding()
        if (reverseAdditions != null) {
            if (result !is Collection<*>) {
                throw WemiException("Key $key has additions but isn't a collection key!")
            }

            val compounded = when (result) {
                is Set<*> ->
                    result.toMutableSet()
                else ->
                    result.toMutableList()
            }

            var i = reverseAdditions.lastIndex
            while (i >= 0) {
                for (lazyAddition in reverseAdditions[i]) {
                    compounded.add(this.lazyAddition())
                }
                i--
            }
            @Suppress("UNCHECKED_CAST")
            result = compounded as Value
        }

        return result
    }

    private inline fun <Value, Output> getKeyValue(key: Key<Value>, otherwise:()->Output):Output where Value : Output {
        var allAdditions:ArrayList<List<LazyKeyValue<Value>>>? = null

        var scope:Scope = this
        while (true) {
            var holder: BindingHolder = scope.scopeBindingHolder
            while(true) {
                @Suppress("UNCHECKED_CAST")
                synchronized(holder.bindLock) {
                    val additions = holder.bindAdditions[key] as List<LazyKeyValue<Value>>?
                    if (additions != null) {
                        if (allAdditions == null) {
                            allAdditions = ArrayList()
                        }
                        allAdditions!!.add(additions)
                    }

                    val lazyValue = holder.binding[key] as LazyKeyValue<Value>?
                    if (lazyValue != null) {
                        return unpack(key, lazyValue, allAdditions)
                    }
                }
                holder = holder.parent?:break
            }
            scope = scope.scopeParent?:break
        }

        return otherwise()
    }

    /** Return the value bound to this wemi.key in this scope.
     * Throws exception if no value set. */
    fun <Value> Key<Value>.get():Value {
        return getKeyValue(this) {
            // We have to check default value
            if (hasDefaultValue) {
                @Suppress("UNCHECKED_CAST")
                defaultValue as Value
            } else {
                throw WemiException.KeyNotAssignedException(this, this@Scope)
            }
        }
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns [unset] if no value set.
     * @param acceptDefault if wemi.key has default value, return that, otherwise return [unset] */
    fun <Value> Key<Value>.getOrElse(unset:Value, acceptDefault:Boolean = true):Value {
        return getKeyValue(this) {
            // We have to check default value
            @Suppress("UNCHECKED_CAST")
            if (acceptDefault && this.hasDefaultValue) {
                this.defaultValue as Value
            } else {
                unset
            }
        }
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns `null` if no value set. */
    fun <Value> Key<Value>.getOrNull():Value? {
        return getKeyValue(this) {
            this.defaultValue
        }
    }

    fun scopeToString():String
}

internal class ConfigurationScope(override val scopeBindingHolder: Configuration, override val scopeParent:Scope) : Scope {
    override fun scopeToString(): String = scopeParent.scopeToString() + scopeBindingHolder.name + ":"
}

sealed class BindingHolder(val parent: BindingHolder?) {
    abstract val name:String

    internal val bindLock = Object()
    internal val binding = HashMap<Key<*>, LazyKeyValue<Any?>>()
    internal val bindAdditions = HashMap<Key<*>, MutableList<LazyKeyValue<Any?>>>()

    infix fun <Value> Key<Value>.set(lazyValue:LazyKeyValue<Value>) {
        @Suppress("UNCHECKED_CAST")
        synchronized(bindLock) {
            binding.put(this as Key<Any>, lazyValue as LazyKeyValue<Any?>)
        }
    }

    operator fun <Value> Key<Collection<Value>>.plusAssign(lazyValue:LazyKeyValue<Value>) {
        @Suppress("UNCHECKED_CAST")
        synchronized(bindLock) {
            val additions = bindAdditions.getOrPut(this as Key<Any>) { mutableListOf<LazyKeyValue<Any?>>() }
            additions.add(lazyValue)
        }
    }
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

fun configuration(description: String, parent: Configuration?, initializer: Configuration.() -> Unit): ConfigurationDelegate {
    return ConfigurationDelegate(description, parent, initializer)
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

fun Scope.kotlinDependency(name: String):ProjectDependency {
    return ProjectDependency(ProjectId("org.jetbrains.kotlin", "kotlin-"+name, Keys.kotlinVersion.get()))
}

fun repository(name: String, url:String, checksum: Repository.M2.Checksum):Repository.M2 {
    val usedUrl = URL(url)
    return Repository.M2(name,
            usedUrl,
            if (usedUrl.protocol.equals("file", ignoreCase = true)) null else LocalM2Repository,
            checksum)
}