@file:Suppress("MemberVisibilityCanPrivate", "MemberVisibilityCanBePrivate")

package wemi

import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import wemi.collections.WMutableList
import wemi.collections.toMutable
import wemi.compile.CompilerFlag
import wemi.compile.CompilerFlags
import wemi.util.*
import java.nio.file.Path
import java.util.*

typealias InputKey = String
typealias InputKeyDescription = String

/** 
 * Key which can have value of type [V] assigned, through [Project] or [Configuration].
 */
class Key<V> internal constructor(
        /**
         * Name of the key. Specified by the variable name this key was declared at.
         * Uniquely describes the key.
         * Having two separate key instances with same name has undefined behavior and will lead to problems.
         */
        val name: String,
        /**
         * Human readable description of this key.
         * Values bound to this key should follow this as a specification.
         */
        val description: String,
        /** True if defaultValue is set, false if not.
         * Needed, because we don't know whether or not is [V] nullable
         * or not, so we need to know if we should return null or not. */
        internal val hasDefaultValue: Boolean,
        /**
         * Default value used for this key, when no other value is bound.
         */
        internal val defaultValue: V?,
        /**
         * Input keys that are used by this key.
         * Used only for documentation and CLI purposes (autocomplete).
         * @see read
         */
        internal val inputKeys: Array<Pair<InputKey, InputKeyDescription>>,
        /**
         * Optional function that can convert the result of this key's evaluation to a more readable
         * or more informative string.
         *
         * Called when the key is evaluated in CLI top level.
         */
        internal val prettyPrinter: ((V) -> CharSequence)?) : WithDescriptiveString, JsonWritable, Comparable<Key<*>> {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as Key<*>

        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    /**
     * Returns [name]
     */
    override fun toString(): String = name

    /**
     * Returns [name] - [description]
     */
    override fun toDescriptiveAnsiString(): String = "${format(name, format = Format.Bold)} - $description"

    override fun compareTo(other: Key<*>): Int {
        return this.name.compareTo(other.name)
    }

    override fun JsonWriter.write() {
        writeObject {
            field("name", name)
            field("description", description)
            field("hasDefaultValue", hasDefaultValue)
        }
    }
}

/**
 * Configuration is a layer of bindings that can be added to the [Scope].
 *
 * Configuration's bound values is the sum of it's [parent]'s values and own values, where own ones override
 * parent ones, if any.
 *
 * @param name of the configuration. Specified by the variable name this configuration was declared at.
 * @param description to be displayed in the CLI as help
 * @param parent of the [Configuration]
 * @see BindingHolder for info about how the values are bound
 */
@WemiDsl
class Configuration internal constructor(val name: String,
                                         val description: String,
                                         val parent: Configuration?)
    : BindingHolder(), JsonWritable {

    /** @return [name] */
    override fun toString(): String {
        return name
    }

    override fun toDescriptiveAnsiString(): String =
            StringBuilder().format(format = Format.Bold).append(name)
                    .format(Color.White).append(':').format().toString()

    override fun JsonWriter.write() {
        writeObject {
            field("name", name)
            field("description", description)
        }
    }
}

/**
 * Holds information about configuration extensions made by [BindingHolder.extend].
 * Values bound to this take precedence over the values [extending],
 * like values in [Configuration] take precedence over those in [Configuration.parent].
 *
 * @param extending which configuration is being extended by this extension
 * @param from which this extension has been created, mostly for debugging
 */
@WemiDsl
class ConfigurationExtension internal constructor(
        @Suppress("MemberVisibilityCanBePrivate")
        val extending: Configuration,
        val from: BindingHolder) : BindingHolder() {

    override fun toDescriptiveAnsiString(): String {
        return StringBuilder()
                .format(format=Format.Bold).append(from.toDescriptiveAnsiString())
                .format(Color.White).append(".extend(").format(format=Format.Bold).append(extending.toDescriptiveAnsiString())
                .format(Color.White).append(") ").format().toString()
    }

    override fun toString(): String = "$from.extend($extending)"
}

/**
 * Configuration is a base binding layer for a [Scope].
 *
 * @param name of the configuration. Specified by the variable name this project was declared at.
 * @param projectRoot at which this [Project] is located at in the filesystem
 * @see BindingHolder for info about how the values are bound
 */
@WemiDsl
class Project internal constructor(val name: String, internal val projectRoot: Path?, archetypes:Array<out Archetype>)
    : BindingHolder(), WithDescriptiveString, JsonWritable {

    /**
     * @return [name]
     */
    override fun toString(): String = name

    /**
     * @return [name] at [projectRoot]
     */
    override fun toDescriptiveAnsiString(): String =
            StringBuilder().format(format = Format.Bold).append(name)
                    .format(Color.White).append('/').format().toString()

    override fun JsonWriter.write() {
        writeValue(name, String::class.java)
    }

    /**
     * Scope for this [Project]. This is where scopes start.
     *
     * @see evaluate to use this
     */
    internal val projectScope: Scope = run {
        val holders = ArrayList<BindingHolder>(1 + archetypes.size * 2) // approximation
        holders.add(this)

        // Iterate through archetypes, most important first, reversed afterwards
        var i = archetypes.lastIndex
        while (i >= 0) {
            var archetype = archetypes[i--]

            while (true) {
                holders.add(archetype)
                archetype = archetype.parent ?: break
            }
        }

        Scope(name, holders, null)
    }

    /** Create base [Scope] in which evaluation tree for this [Project] can start. */
    @PublishedApi
    internal fun scopeFor(vararg configurations:Configuration):Scope {
        var scope = projectScope
        for (config in configurations) {
            scope = scope.scopeFor(config)
        }
        return scope
    }

    /**
     * Evaluate the [action] in the scope of this [Project].
     * This is the entry-point to the key-evaluation mechanism.
     *
     * Keys can be evaluated only inside the [action].
     *
     * @param configurations that should be applied to this [Project]'s [Scope] before [action] is run in it.
     *          It is equivalent to calling [Scope.using] directly in the [action], but more convenient.
     */
    inline fun <Result> evaluate(vararg configurations:Configuration, action:EvalScope.()->Result):Result {
        try {
            evaluateLock()
            return EvalScope(this.scopeFor(*configurations), NO_CONFIGURATIONS, ArrayList(), ArrayList(), NO_INPUT).run(action)
        } finally {
            evaluateUnlock()
        }
    }

    companion object {

        @Volatile
        private var currentlyEvaluatingThread:Thread? = null
        private var currentlyEvaluatingNestLevel = 0

        @PublishedApi
        internal fun evaluateLock() {
            // Ensure that this thread is the only thread that can be evaluating
            synchronized(this@Companion) {
                val currentThread = Thread.currentThread()
                if (currentlyEvaluatingThread == null) {
                    currentlyEvaluatingThread = currentThread
                } else if (currentlyEvaluatingThread != currentThread) {
                    throw IllegalStateException("Can't evaluate from $currentThread, already evaluating from $currentlyEvaluatingThread")
                }
            }
            currentlyEvaluatingNestLevel++
        }

        @PublishedApi
        internal fun evaluateUnlock() {
            currentlyEvaluatingNestLevel--
            assert(currentlyEvaluatingNestLevel >= 0)
            if (currentlyEvaluatingNestLevel == 0) {
                // Release the thread lock
                synchronized(this@Companion) {
                    assert(currentlyEvaluatingThread == Thread.currentThread())
                    currentlyEvaluatingThread = null
                }
                globalTickTime++
            }
        }
    }
}

/**
 * Contains a collection of default key settings, that are common for all [Project]s of this archetype.
 *
 * Archetype usually specifies which languages can be used and what the compile output is.
 *
 * @param name used mostly for debugging
 * @see Archetypes for more info about this concept
 */
@WemiDsl
class Archetype internal constructor(val name: String, val parent:Archetype?) : BindingHolder() {

    override fun toDescriptiveAnsiString(): String {
        val sb = StringBuilder()
        if (parent != null) {
            sb.format(Color.White).append('(')
            val parentStack = ArrayList<Archetype>()
            var parent = this.parent
            while (parent != null) {
                parentStack.add(parent)
                parent = parent.parent
            }
            for (i in parentStack.indices.reversed()) {
                sb.append(parentStack[i].name).append("//")
            }
            sb.append(')')
        }
        sb.format(format = Format.Bold).append(name).append("//").format()
        return sb.toString()
    }

    override fun toString(): String = "$name//"
}

/**
 * Defines a part of scope in the scope linked-list structure.
 * Scope allows to query the values of bound [Keys].
 * Each scope is internally formed by an ordered list of [BindingHolder]s.
 *
 * @param bindingHolders list of holders contributing to the scope's holder stack. Most significant holders first.
 * @param scopeParent of the scope, only [Project] may have null parent.
 */
class Scope internal constructor(
        private val name: String,
        val bindingHolders: List<BindingHolder>,
        val scopeParent: Scope?) {

    private val configurationScopeCache: MutableMap<Configuration, Scope> = HashMap()

    /** When key is evaluated, it needs a [Binding].
     * This cache contains only already at-least once evaluated bindings. */
    internal val keyBindingCache:MutableMap<Key<*>, Binding<*>> = HashMap()

    @PublishedApi
    internal fun scopeFor(configuration: Configuration): Scope {
        val scopes = configurationScopeCache
        synchronized(scopes) {
            return scopes.getOrPut(configuration) {
                /*
                Scope for configuration consists of flattened BindingHolders, and a given parent scope.
                Then the configuration's parents are layered on top:
                1. From oldest ancestor of [configuration] to [configuration]
                    1. Take it (some configuration) and add it
                    2. If any holder which already exists extends it, add it on top
                 */

                // Most significant are last, least significant are first
                val newScopeHolders = ArrayList<BindingHolder>()

                val holderStack:List<List<BindingHolder>> = ArrayList<List<BindingHolder>>().apply {
                    var scope = this@Scope
                    while (true) {
                        add(scope.bindingHolders)
                        scope = scope.scopeParent ?: break
                    }
                    reverse()
                }

                fun addScopeHolderElementsFor(configuration:Configuration, addTo:MutableList<BindingHolder>, existingChain:List<List<BindingHolder>>) {
                    if (configuration.parent != null) {
                        addScopeHolderElementsFor(configuration.parent, addTo, existingChain)
                    }

                    addTo.add(configuration)
                    for (holders in existingChain) {
                        for (holder in holders) {
                            addTo.add(holder.configurationExtensions[configuration] ?: continue)
                        }
                    }
                }

                addScopeHolderElementsFor(configuration, newScopeHolders, holderStack)
                newScopeHolders.reverse()

                Scope(configuration.name, newScopeHolders, this)
            }
        }
    }

    private fun <T> createElementaryKeyBinding(key:Key<T>, listener:WemiKeyEvaluationListener?):Binding<T>? {
        val boundValue: Value<T>?
        val boundValueOriginScope: Scope?
        val boundValueOriginHolder: BindingHolder?
        val allModifiersReverse = ArrayList<ValueModifier<T>>()

        var scope: Scope = this

        searchForBoundValue@while (true) {
            // Retrieve the holder

            for (holder in scope.bindingHolders) {
                val holderModifiers = holder.modifierBindings[key]
                if (holderModifiers != null && holderModifiers.isNotEmpty()) {
                    listener?.keyEvaluationHasModifiers(scope, holder, holderModifiers.size)
                    @Suppress("UNCHECKED_CAST")
                    allModifiersReverse.addAllReversed(holderModifiers as ArrayList<ValueModifier<T>>)
                }

                val boundValueCandidate = holder.binding[key]
                if (boundValueCandidate != null) {
                    @Suppress("UNCHECKED_CAST")
                    boundValue = boundValueCandidate as Value<T>
                    boundValueOriginHolder = holder
                    boundValueOriginScope = scope
                    break@searchForBoundValue
                }
            }

            scope = scope.scopeParent ?: if (key.hasDefaultValue) {
                boundValue = null
                boundValueOriginHolder = null
                boundValueOriginScope = null
                break@searchForBoundValue
            } else {
                return null
            }
        }

        val modifiers:Array<ValueModifier<T>> =
                if (allModifiersReverse.size == 0) {
                    @Suppress("UNCHECKED_CAST")
                    NO_BINDING_MODIFIERS as Array<ValueModifier<T>>
                } else Array(allModifiersReverse.size) {
                    allModifiersReverse[allModifiersReverse.size - it - 1]
                }
        return Binding(key, boundValue, modifiers, boundValueOriginScope, boundValueOriginHolder)
    }

    internal fun <T> getKeyBinding(key:Key<T>, listener:WemiKeyEvaluationListener?):Binding<T>? {
        // Put into the cache after successful evaluation
        keyBindingCache[key]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as Binding<T>
        }

        return createElementaryKeyBinding(key, listener)
    }

    /**
     * @return [Project] that is at the root of this [Scope]
     */
    fun scopeProject():Project {
        // Project is always in the scope without parent
        var scope = this
        while (true) {
            scope = scope.scopeParent ?: break
        }
        for (holder in scope.bindingHolders) {
            // Should be the last one...
            if (holder is Project) {
                return holder
            }
        }
        throw IllegalStateException("No Project in Scope")
    }

    /**
     * Forget cached values stored in this and descendant caches.
     *
     * @return amount of values forgotten
     */
    internal fun cleanCache(): Int {
        val sum = keyBindingCache.size + configurationScopeCache.values.sumBy { it.cleanCache() }
        keyBindingCache.clear()
        return sum
    }

    private fun buildToString(sb: StringBuilder) {
        scopeParent?.buildToString(sb)
        sb.append(name)
        if (scopeParent == null) {
            sb.append('/')
        } else {
            sb.append(':')
        }
    }

    /**
     * @return scope in the standard syntax, i.e. project/config1:config2:
     */
    override fun toString(): String {
        val sb = StringBuilder()
        buildToString(sb)
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Scope

        if (name != other.name) return false
        if (bindingHolders != other.bindingHolders) return false
        if (scopeParent != other.scopeParent) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + bindingHolders.hashCode()
        result = 31 * result + (scopeParent?.hashCode() ?: 0)
        return result
    }
}

private val LOG: Logger = LoggerFactory.getLogger("BindingHolder")

/**
 * Holds [Key] value bindings (through [Value]),
 * key modifiers (through [ValueModifier]),
 * and [ConfigurationExtension] extensions (through [ConfigurationExtension]).
 *
 * Also provides ways to set them during the object's initialization.
 * After the initialization, the holder is locked and no further modifications are allowed.
 *
 * [BindingHolder] instances form building elements of a [Scope].
 */
sealed class BindingHolder : WithDescriptiveString {

    internal val binding = HashMap<Key<*>, Value<Any?>>()
    internal val modifierBindings = HashMap<Key<*>, ArrayList<ValueModifier<Any?>>>()
    internal val configurationExtensions = HashMap<Configuration, ConfigurationExtension>()
    internal var locked = false

    private fun ensureUnlocked() {
        if (locked) throw IllegalStateException("Binding holder $this is already locked")
    }

    /**
     * Bind given [value] to the receiver [Key] for this scope.
     * [value] will be evaluated every time someone queries receiver key and this [BindingHolder] is
     * the topmost in the query [Scope] with the key bound.
     *
     * If the key already has [Value] bound in this scope, it will be replaced.
     *
     * @see modify
     */
    infix fun <V> Key<V>.set(value: Value<V>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        val old = binding.put(this as Key<Any>, value as Value<Any?>)
        if (old != null) {
            LOG.debug("Overriding previous value bound to {} in {}", this, this@BindingHolder)
        }
    }

    /**
     * Add given [valueModifier] to the list of receiver key modifiers for this scope.
     *
     * When the key is queried and obtained in this or any less significant [BindingHolder] in the [Scope],
     * [valueModifier] are all evaluated on the obtained value, from those in least significant [BindingHolder]s
     * first, those added earlier first, up to the last-added, most-significant modifier in [Scope].
     *
     * First modifier will receive as an argument result of the [Value], second the result of first, and so on.
     * Result of last modifier will be then used as a definitive result of the key query.
     *
     * @param valueModifier to be added
     * @see set
     */
    infix fun <V> Key<V>.modify(valueModifier: ValueModifier<V>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        val modifiers = modifierBindings.getOrPut(this as Key<Any>) { ArrayList() } as ArrayList<ValueModifier<*>>
        modifiers.add(valueModifier)
    }

    /**
     * Extend given configuration so that when it is accessed with this [BindingHolder] in [Scope],
     * given [ConfigurationExtension] will be queried for bindings first.
     *
     * @param configuration to extend
     * @param initializer that will be executed to populate the configuration
     */
    @Suppress("MemberVisibilityCanPrivate")
    fun extend(configuration: Configuration, initializer: ConfigurationExtension.() -> Unit) {
        ensureUnlocked()
        val extensions = this@BindingHolder.configurationExtensions
        val extension = extensions.getOrPut(configuration) { ConfigurationExtension(configuration, this) }
        extension.locked = false
        extension.initializer()
        extension.locked = true
    }

    /**
     * [extend] multiple configurations at the same time.
     * ```
     * extendMultiple(a, b, c, init)
     * ```
     * is equivalent to
     * ```
     * extend(a, init)
     * extend(b, init)
     * extend(c, init)
     * ```
     */
    fun extendMultiple(vararg configurations: Configuration, initializer: ConfigurationExtension.() -> Unit) {
        for (configuration in configurations) {
            extend(configuration, initializer)
        }
    }

    //region Modify utility methods
    /** [modify] receiver to also contain [additionalValue]'s result */
    @JvmName("addSet")
    inline infix fun <V> Key<List<V>>.add(crossinline additionalValue: Value<V>) {
        this.modify { it.toMutable().apply { add(additionalValue()) } }
    }

    @JvmName("addList")
    inline infix fun <V> Key<Set<V>>.add(crossinline additionalValue: Value<V>) {
        this.modify { it.toMutable().apply { add(additionalValue()) } }
    }

    /** [modify] receiver to also contain [additionalValues]'s result */
    @JvmName("addAllList")
    inline infix fun <V> Key<List<V>>.addAll(crossinline additionalValues: Value<Iterable<V>>) {
        this.modify { it.toMutable().apply { addAll(additionalValues()) } }
    }

    @JvmName("addAllSet")
    inline infix fun <V> Key<Set<V>>.addAll(crossinline additionalValues: Value<Iterable<V>>) {
        this.modify { it.toMutable().apply { addAll(additionalValues()) } }
    }

    /**
     * [modify] receiver to remove the result of [valueToRemove] from the resulting [Set].
     *
     * Defined on [Set] only, because operating on [List] may have undesired effect of removing
     * only one occurrence of [valueToRemove].
     */
    inline infix fun <V> Key<Set<V>>.remove(crossinline valueToRemove: Value<V>) {
        this.modify { it.toMutable().apply { remove(valueToRemove()) } }
    }

    /** [modify] receiver to remove any values which also are in [valuesToRemove]'s result */
    @JvmName("removeAllList")
    inline infix fun <V> Key<List<V>>.removeAll(crossinline valuesToRemove: Value<Iterable<V>>) {
        this.modify { it.toMutable().apply { removeAll(valuesToRemove()) } }
    }

    @JvmName("removeAllSet")
    inline infix fun <V> Key<Set<V>>.removeAll(crossinline valuesToRemove: Value<Iterable<V>>) {
        this.modify { it.toMutable().apply { removeAll(valuesToRemove()) } }
    }
    //endregion

    //region CompilerFlags utility methods
    /**
     * @see BindingHolder.plusAssign
     * @see BindingHolder.minusAssign
     */
    operator fun <Type> Key<CompilerFlags>.get(flag: CompilerFlag<List<Type>>): CompilerFlagKeySetting<List<Type>> {
        return CompilerFlagKeySetting(this, flag)
    }

    /**
     * Modify [CompilerFlags] to set the given compiler [flag] to the given [value].
     *
     * @see modify
     */
    operator fun <Type> Key<CompilerFlags>.set(flag: CompilerFlag<Type>, value: Type) {
        this.modify { flags: CompilerFlags ->
            flags[flag] = value
            flags
        }
    }

    /**
     * Modify [CompilerFlags] to set the given compiler [flag] to the given [value]
     * that will be evaluated as if it was a key binding.
     *
     * @see modify
     */
    operator fun <Type> Key<CompilerFlags>.set(flag: CompilerFlag<Type>, value: Value<Type>) {
        this.modify { flags: CompilerFlags ->
            flags[flag] = value()
            flags
        }
    }

    /**
     * Modify [CompilerFlags] to add given [value] to the collection
     * assigned to the compiler flag of [CompilerFlagKeySetting].
     *
     * @see modify
     */
    operator fun <Type> CompilerFlagKeySetting<List<Type>>.plusAssign(value: Type) {
        key.modify { flags: CompilerFlags ->
            flags[flag].let {
                val mutable = it?.toMutable() ?: WMutableList()
                mutable.add(value)
                flags[flag] = mutable
            }
            flags
        }
    }

    /**
     * Modify [CompilerFlags] to remove given [value] from the collection
     * assigned to the compiler flag of [CompilerFlagKeySetting].
     *
     * @see modify
     */
    @Suppress("MemberVisibilityCanPrivate")
    operator fun <Type> CompilerFlagKeySetting<List<Type>>.minusAssign(value: Type) {
        key.modify { flags: CompilerFlags ->
            flags[flag]?.let {
                if (it.size == 1 && it[0] == value) {
                    flags.unset(flag)
                } else {
                    val mutable = it.toMutable()
                    mutable.remove(value)
                    flags[flag] = mutable
                }
            }
            flags
        }
    }

    /**
     * Boilerplate for [BindingHolder.plusAssign] and [BindingHolder.minusAssign].
     */
    class CompilerFlagKeySetting<Type> internal constructor(
            internal val key: Key<CompilerFlags>,
            internal val flag: CompilerFlag<Type>)
    //endregion

    /**
     * One line string, using only White foreground for non-important stuff and Bold for important stuff.
     */
    abstract override fun toDescriptiveAnsiString(): String
}

/**
 * Special [Value] for values that should be evaluated only once per whole binding,
 * regardless of Scope. Use only for values with no dependencies on any modifiable input.
 *
 * Example:
 * ```kotlin
 * projectName set Static("MyProject")
 * ```
 *
 * @see LazyStatic for values that are in this nature, but their computation is not trivial
 */
class Static<V>(private val value:V) : (EvalScope) -> V {
    override fun invoke(ignored: EvalScope): V {
        activeKeyEvaluationListener?.keyEvaluationFeature("static")
        return value
    }
}

/**
 * Special [Value] for values that should be evaluated only once, but lazily.
 * Similar to [Static] in nature of supported values.
 *
 * Example:
 * ```kotlin
 * heavyResource set LazyStatic { createHeavyResource() }
 * ```
 */
class LazyStatic<V>(generator:()->V) : (EvalScope) -> V {
    private var generator:(()->V)? = generator
    private var cachedValue:V? = null

    override fun invoke(scope: EvalScope): V {
        val generator = this.generator
        val value:V
        @Suppress("UNCHECKED_CAST")
        if (generator != null) {
            value = generator.invoke()
            this.cachedValue = value
            this.generator = null
            activeKeyEvaluationListener?.keyEvaluationFeature("first lazy static")
        } else {
            activeKeyEvaluationListener?.keyEvaluationFeature("lazy static")
            value = this.cachedValue as V
        }
        return value
    }
}