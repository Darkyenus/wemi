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
                                          internal val defaultValue:Value?,
                                          internal val cached:Boolean) : WithDescriptiveString {

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

/** Standard function type that is bound as value to the key in [BindingHolder] */
typealias BoundKeyValue<Value> = Scope.() -> Value
/** Value modifier that can be additionally bound to a key in [BindingHolder] */
typealias BoundKeyValueModifier<Value> = Scope.(Value) -> Value

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

    override fun previous(): Scope = NullScope

    override fun toString(): String = name

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} at $projectRoot"

    override val scopeCache: ScopeCache = ScopeCache(this, null)
}

interface Scope {

    val scopeCache:ScopeCache

    fun <Result> with(configuration: Configuration, action: Scope.() -> Result):Result {
        val scope = scopeCache.scope(this, configuration)
        return scope.action()
    }

    private inline fun <Value>unpack(scope: Scope, key: Key<Value>, binding: BoundKeyValue<Value>, reverseModifiers:ArrayList<BoundKeyValueModifier<Value>>?):Value {
        var result = this.binding()
        if (reverseModifiers != null) {
            if (result !is Collection<*>) {
                throw WemiException("Key $key has additions but isn't a collection key!")
            }

            var i = reverseModifiers.lastIndex
            while (i >= 0) {
                result = reverseModifiers[i].invoke(this, result)
                i--
            }
        }

        scope.scopeCache.putCached(key, result)

        return result
    }

    private inline fun <Value, Output> getKeyValue(key: Key<Value>, otherwise:()->Output):Output where Value : Output {
        val cachedKey = key.cached
        var allModifiersReverse:ArrayList<BoundKeyValueModifier<Value>>? = null

        var scope:Scope = this
        while (true) {
            val scopeCache = scope.scopeCache
            if (cachedKey) {
                val maybeCachedValue = scopeCache.getCached(key)
                if (maybeCachedValue != null) {
                    return maybeCachedValue
                }
            }
            var holder: BindingHolder = scopeCache.bindingHolder
            @Suppress("UNCHECKED_CAST")
            while(true) {
                val holderModifiers = holder.modifierBindings[key] as ArrayList<BoundKeyValueModifier<Value>>?
                if (holderModifiers != null) {
                    if (allModifiersReverse == null) {
                        allModifiersReverse = ArrayList()
                    }
                    allModifiersReverse.ensureCapacity(holderModifiers.size)
                    var i = holderModifiers.size - 1
                    while (i >= 0) {
                        allModifiersReverse.add(holderModifiers[i])
                        i--
                    }
                }

                val lazyValue = holder.binding[key] as BoundKeyValue<Value>?
                if (lazyValue != null) {
                    return unpack(scope, key, lazyValue, allModifiersReverse)
                }
                holder = holder.parent?:break
            }
            scope = scopeCache.parentScope ?:break
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

    fun previous():Scope

    fun scopeToString():String
}

/** Immutable empty Scope returned by previous() on root scopes. */
private object NullScope : BindingHolder(null), Scope {
    override val name: String
        get() = "NullScope"

    override val scopeCache: ScopeCache = ScopeCache(this, null)

    override fun previous(): Scope = this

    override fun scopeToString(): String = name

    init {
        locked = true
    }
}

sealed class BindingHolder(val parent: BindingHolder?) {
    abstract val name:String

    internal val binding = HashMap<Key<*>, BoundKeyValue<Any?>>()
    internal val modifierBindings = HashMap<Key<*>, ArrayList<BoundKeyValueModifier<Any?>>>()
    internal var locked = false

    private fun ensureUnlocked() {
        if (locked) throw IllegalStateException("Binding holder $name is already locked")
    }

    infix fun <Value> Key<Value>.set(lazyValue: BoundKeyValue<Value>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        binding.put(this as Key<Any>, lazyValue as BoundKeyValue<Any?>)
    }

    infix fun <Value> Key<Value>.modify(lazyValueModifier: BoundKeyValueModifier<Value>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        val modifiers = modifierBindings.getOrPut(this as Key<Any>) { ArrayList() } as ArrayList<BoundKeyValueModifier<*>>
        modifiers.add(lazyValueModifier)
    }

    infix fun <Value> Key<Collection<Value>>.add(lazyValueModifier: BoundKeyValue<Value>) {
        this.modify { collection:Collection<Value> ->
            //TODO Optimize?
            collection + lazyValueModifier()
        }
    }

    infix fun <Value> Key<Collection<Value>>.remove(lazyValueModifier: BoundKeyValue<Value>) {
        this.modify { collection:Collection<Value> ->
            collection - lazyValueModifier()
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