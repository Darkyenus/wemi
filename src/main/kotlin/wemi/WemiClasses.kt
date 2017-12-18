@file:Suppress("unused")

package wemi

import com.esotericsoftware.jsonbeans.Json
import wemi.boot.CLI
import wemi.boot.MachineWritable
import wemi.compile.CompilerFlag
import wemi.compile.CompilerFlags
import wemi.util.WithDescriptiveString
import java.io.File

/** wemi.Key which can have value of type [Value] assigned, through [Project] or [Configuration]. */
class Key<Value> internal constructor(val name:String,
                                          val description:String,
                                          /** True if defaultValue is set, false if not.
                                           * Needed, because we don't know whether or not is [Value] nullable
                                           * or not, so we need to know if we should return null or not. */
                                          internal val hasDefaultValue:Boolean,
                                          internal val defaultValue:Value?,
                                          internal val cached:Boolean,
                                          internal val prettyPrinter:((Value) -> CharSequence)?) : WithDescriptiveString, MachineWritable {

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

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("name", name, String::class.java)
        json.writeValue("description", description, String::class.java)
        json.writeValue("hasDefaultValue", hasDefaultValue, Boolean::class.java)
        json.writeValue("cached", cached, Boolean::class.java)
        json.writeObjectEnd()
    }
}

class Configuration internal constructor(val name: String,
                                         val description: String,
                                         parent: Configuration?)
    : BindingHolder(parent), WithDescriptiveString, MachineWritable {

    override fun toString(): String {
        return name
    }

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} - \"$description\""

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("name", name, String::class.java)
        json.writeValue("description", description, String::class.java)
        json.writeValue("parent", (parent as Configuration?)?.name, String::class.java)
        json.writeObjectEnd()
    }
}

private val AnonymousConfigurationDescriptiveAnsiString = CLI.format("<anonymous>", format = CLI.Format.Bold).toString()

class AnonymousConfiguration @PublishedApi internal constructor(parent: Configuration?) : BindingHolder(parent), WithDescriptiveString {
    override fun toDescriptiveAnsiString(): String = AnonymousConfigurationDescriptiveAnsiString
}

class ConfigurationExtension internal constructor(extending: Configuration) : BindingHolder(extending)

class Project internal constructor(val name: String, val projectRoot: File)
    : BindingHolder(null), WithDescriptiveString, MachineWritable {

    override fun toString(): String = name

    override fun toDescriptiveAnsiString(): String = "${CLI.format(name, format = CLI.Format.Bold)} at $projectRoot"

    override fun writeMachine(json: Json) {
        json.writeValue(name as Any, String::class.java)
    }

    private val scopeCache: ScopeCache = ScopeCache(this, null)

    val projectScope:Scope = object:Scope {
        override fun scopeToString(): String = name + "/"

        override val scopeCache: ScopeCache = this@Project.scopeCache
    }
}

inline fun <Result> Scope.using(configuration: Configuration, action: Scope.() -> Result):Result {
    val scope = scopeCache.scope(this, configuration)
    return scope.action()
}

inline fun <Result> Scope.using(anonInitializer:AnonymousConfiguration.()->Unit,
                   parent: Configuration? = null, action:Scope.() -> Result):Result {
    val anonConfig = AnonymousConfiguration(parent)
    anonConfig.anonInitializer()
    val scope = scopeCache.scope(this, anonConfig)
    return scope.action()
}

interface Scope {

    val scopeCache:ScopeCache

    private inline fun <Value>unpack(scope: Scope, key: Key<Value>, binding: BoundKeyValue<Value>, reverseModifiers:ArrayList<BoundKeyValueModifier<Value>>?):Value {
        var result = this.binding()
        if (reverseModifiers != null) {
            var i = reverseModifiers.lastIndex
            while (i >= 0) {
                result = reverseModifiers[i].invoke(this, result)
                i--
            }
        }

        if (key.cached) {
            scope.scopeCache.putCached(key, result)
        }

        return result
    }

    private inline fun <Value, Output> getKeyValue(key: Key<Value>, otherwise:()->Output):Output where Value : Output {
        var allModifiersReverse:ArrayList<BoundKeyValueModifier<Value>>? = null

        var scope:Scope = this
        while (true) {
            // Check the cache
            val scopeCache = scope.scopeCache
            if (key.cached) {
                val maybeCachedValue = scopeCache.getCached(key)
                if (maybeCachedValue != null) {
                    return maybeCachedValue
                }
            }

            // Retrieve the holder
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
                    return unpack(this, key, lazyValue, allModifiersReverse)
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

    fun scopeToString():String
}

sealed class BindingHolder(val parent: BindingHolder?) {

    internal val binding = HashMap<Key<*>, BoundKeyValue<Any?>>()
    internal val modifierBindings = HashMap<Key<*>, ArrayList<BoundKeyValueModifier<Any?>>>()
    internal val configurationExtensions = HashMap<Configuration, ConfigurationExtension>()
    internal var locked = false

    private fun ensureUnlocked() {
        if (locked) throw IllegalStateException("Binding holder $this is already locked")
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

    fun extend(configuration: Configuration, initializer:ConfigurationExtension.() -> Unit) {
        ensureUnlocked()
        val extensions = this@BindingHolder.configurationExtensions
        if (extensions.containsKey(this)) {
            throw IllegalStateException("Configuration $configuration already extended in ${this@BindingHolder}")
        }
        val extension = ConfigurationExtension(configuration)
        extension.initializer()
        extension.locked = true
        extensions[configuration] = extension
    }

    //region Modify utility methods
    infix inline fun <Value> Key<Collection<Value>>.add(crossinline lazyValueModifier: BoundKeyValue<Value>) {
        this.modify { collection:Collection<Value> ->
            //TODO Optimize?
            collection + lazyValueModifier()
        }
    }

    infix inline fun <Value> Key<Collection<Value>>.remove(crossinline lazyValueModifier: BoundKeyValue<Value>) {
        this.modify { collection:Collection<Value> ->
            collection - lazyValueModifier()
        }
    }
    //endregion

    //region CompilerFlags utility methods
    operator fun <Type> Key<CompilerFlags>.get(flag:CompilerFlag<Type>):CompilerFlagKeySetting<Type> {
        return CompilerFlagKeySetting(this, flag)
    }

    operator fun <Type> Key<CompilerFlags>.set(flag: CompilerFlag<Type>, value:Type) {
        this.modify { flags: CompilerFlags ->
            flags[flag] = value
            flags
        }
    }

    operator fun <Type> CompilerFlagKeySetting<Collection<Type>>.plusAssign(value:Type) {
        key.modify { flags: CompilerFlags ->
            flags[flag] = (flags[flag]?: emptyList()) + value
            flags
        }
    }


    class CompilerFlagKeySetting<Type> internal constructor(
            internal val key:Key<CompilerFlags>,
            internal val flag:CompilerFlag<Type>)
    //endregion
}

