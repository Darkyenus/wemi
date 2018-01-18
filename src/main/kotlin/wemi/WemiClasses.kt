@file:Suppress("unused", "MemberVisibilityCanPrivate")

package wemi

import com.esotericsoftware.jsonbeans.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import wemi.boot.MachineWritable
import wemi.compile.CompilerFlag
import wemi.compile.CompilerFlags
import wemi.util.Format
import wemi.util.WithDescriptiveString
import wemi.util.format
import java.nio.file.Path

/** 
 * Key which can have value of type [Value] assigned, through [Project] or [Configuration].
 */
class Key<Value> internal constructor(
        /**
         * Name of the key. Specified by the variable name this key was declared at.
         */
        val name: String,
        /**
         * Human readable description of this key.
         * Values bound to this key should follow this as a specification.
         */
        val description: String,
        /** True if defaultValue is set, false if not.
         * Needed, because we don't know whether or not is [Value] nullable
         * or not, so we need to know if we should return null or not. */
        internal val hasDefaultValue: Boolean,
        /**
         * Default value used for this key, when no other value is bound.
         */
        internal val defaultValue: Value?,
        /**
         * True if results of this key's evaluation should be cached by the scope in which it was evaluated.
         */
        internal val cached: Boolean,
        /**
         * Optional function that can convert the result of this key's evaluation to a more readable
         * or more informative string.
         *
         * Called when the key is evaluated in CLI top level.
         */
        internal val prettyPrinter: ((Value) -> CharSequence)?) : WithDescriptiveString, MachineWritable {

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

    /**
     * Returns [name]
     */
    override fun toString(): String {
        return name
    }

    /**
     * Returns [name] - [description]
     */
    override fun toDescriptiveAnsiString(): String = "${format(name, format = Format.Bold)} - $description"

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("name", name, String::class.java)
        json.writeValue("description", description, String::class.java)
        json.writeValue("hasDefaultValue", hasDefaultValue, Boolean::class.java)
        json.writeValue("cached", cached, Boolean::class.java)
        json.writeObjectEnd()
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
class Configuration internal constructor(val name: String,
                                         val description: String,
                                         val parent: Configuration?)
    : BindingHolder(), WithDescriptiveString, MachineWritable {

    /**
     * @return [name]
     */
    override fun toString(): String {
        return name
    }

    /**
     * @return [name] - [description]
     */
    override fun toDescriptiveAnsiString(): String = "${format(name, format = Format.Bold)} - \"$description\""

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("name", name, String::class.java)
        json.writeValue("description", description, String::class.java)
        json.writeObjectEnd()
    }
}

private val AnonymousConfigurationDescriptiveAnsiString = format("<anonymous>", format = Format.Bold).toString()

/**
 * A special version of [Configuration] that is anonymous and can be created at runtime, any time.
 * Unlike full configuration, does not have name, description, nor parent.
 *
 * @see [Scope.using] about creation of these
 */
class AnonymousConfiguration @PublishedApi internal constructor() : BindingHolder(), WithDescriptiveString {
    /**
     * @return <anonymous>
     */
    override fun toDescriptiveAnsiString(): String = AnonymousConfigurationDescriptiveAnsiString

    override fun toString(): String = "<anonymous>"
}

/**
 * Holds information about configuration extensions made by [BindingHolder.extend].
 * Values bound to this take precedence over the values [extending],
 * like values in [Configuration] take precedence over those in [Configuration.parent].
 *
 * @param extending which configuration is being extended by this extension
 * @param from which this extension has been created, mostly for debugging
 */
class ConfigurationExtension internal constructor(val extending: Configuration, val from: BindingHolder) : BindingHolder() {
    /**
     * @return extend([extending]) from [from]
     */
    override fun toString(): String = "extend($extending) from $from"
}

/**
 * Configuration is a base binding layer for a [Scope].
 *
 * @param name of the configuration. Specified by the variable name this project was declared at.
 * @param projectRoot at which this [Project] is located at in the filesystem
 * @see BindingHolder for info about how the values are bound
 */
class Project internal constructor(val name: String, val projectRoot: Path)
    : BindingHolder(), WithDescriptiveString, MachineWritable {

    /**
     * @return [name]
     */
    override fun toString(): String = name

    /**
     * @return [name] at [projectRoot]
     */
    override fun toDescriptiveAnsiString(): String = "${format(name, format = Format.Bold)} at $projectRoot"

    override fun writeMachine(json: Json) {
        json.writeValue(name as Any, String::class.java)
    }

    /**
     * Scope for this [Project]. This is how where scopes start.
     */
    val projectScope: Scope = Scope(name, listOf(this), null)
}

/**
 * Defines a part of scope in the scope linked-list structure.
 * Scope allows to query the values of bound [Keys].
 * Each scope is internally formed by an ordered list of [BindingHolder]s.
 *
 * @param scopeBindingHolders list of holders contributing to the scope's holder stack. Most significant holders first.
 * @param scopeParent of the scope, only [Project] may have null parent.
 */
class Scope internal constructor(
        private val name: String,
        val scopeBindingHolders: List<BindingHolder>,
        val scopeParent: Scope?) {

    private val configurationScopeCache: MutableMap<Configuration, Scope> = java.util.HashMap()

    private var valueCache: MutableMap<Key<*>, Any?>? = null

    private inline fun traverseHoldersBack(action: (BindingHolder) -> Unit) {
        var scope = this
        while (true) {
            scope.scopeBindingHolders.forEach(action)
            scope = scope.scopeParent ?: break
        }
    }

    @PublishedApi
    internal fun scopeFor(configuration: Configuration): Scope {
        val scopes = configurationScopeCache
        synchronized(scopes) {
            return scopes.getOrPut(configuration) {
                val newScopeHolders = ArrayList<BindingHolder>()

                var conf = configuration
                while (true) {
                    // Configuration may have been extended, add extensions
                    traverseHoldersBack { holder ->
                        val extension = holder.configurationExtensions[conf]
                        if (extension != null) {
                            newScopeHolders.add(extension)
                        }
                    }
                    // Extensions added, now add the configuration itself
                    newScopeHolders.add(conf)

                    // Add configuration's parents, if any, with lesser priority than the configuration itself
                    conf = conf.parent ?: break
                }

                Scope(configuration.name, newScopeHolders, this)
            }
        }
    }

    @PublishedApi
    internal fun scopeFor(anonymousConfiguration: AnonymousConfiguration): Scope {
        // Cannot be extended
        anonymousConfiguration.locked = true // Here because of visibility rules
        return Scope("<anonymous>", listOf(anonymousConfiguration), this)
    }

    /**
     * Run the [action] in a scope, which is created by layering [configurations] over this [Scope].
     */
    inline fun <Result> Scope.using(vararg configurations: Configuration, action: Scope.() -> Result): Result {
        var scope = this
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return scope.action()
    }

    /**
     * Run the [action] in a scope, which is created by layering [configurations] over this [Scope].
     */
    inline fun <Result> Scope.using(configurations: Collection<Configuration>, action: Scope.() -> Result): Result {
        var scope = this
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return scope.action()
    }

    /**
     * Run the [action] in a scope, which is created by layering [configuration] over this [Scope].
     */
    inline fun <Result> Scope.using(configuration: Configuration, action: Scope.() -> Result): Result {
        return scopeFor(configuration).action()
    }

    /**
     * Run the [action] in a scope, which is created by layering new anonymous configuration,
     * created by the [anonInitializer], over this [Scope].
     *
     * @param anonInitializer initializer of the anonymous scope. Works exactly like standard [Configuration] initializer
     */
    inline fun <Result> Scope.using(anonInitializer: AnonymousConfiguration.() -> Unit,
                                    action: Scope.() -> Result): Result {
        val anonConfig = AnonymousConfiguration()
        anonConfig.anonInitializer()
        // Locking is deferred to scopeFor because of visibility rules for inlined functions
        return scopeFor(anonConfig).action()
    }

    private fun <Value : Output, Output> getKeyValue(key: Key<Value>, otherwise: Output, useOtherwise: Boolean): Output {
        val listener = activeKeyEvaluationListener
        listener?.keyEvaluationStarted(this, key)

        // Check the cache
        if (key.cached) {
            val maybeCachedValue = getCached(key)
            if (maybeCachedValue != null) {
                listener?.keyEvaluationSucceeded(this, null, maybeCachedValue)
                return maybeCachedValue
            }
        }

        var foundValue: Value? = key.defaultValue
        var foundValueValid = key.hasDefaultValue
        var holderOfFoundValue:BindingHolder? = null
        val allModifiersReverse: ArrayList<BoundKeyValueModifier<Value>> = ArrayList()

        var scope: Scope? = this

        searchForValue@ while (scope != null) {
            // Retrieve the holder
            @Suppress("UNCHECKED_CAST")
            for (holder in scope.scopeBindingHolders) {
                val holderModifiers = holder.modifierBindings[key] as ArrayList<BoundKeyValueModifier<Value>>?
                if (holderModifiers != null && holderModifiers.isNotEmpty()) {
                    listener?.keyEvaluationHasModifiers(scope, holder, holderModifiers.size)

                    allModifiersReverse.ensureCapacity(holderModifiers.size)
                    var i = holderModifiers.size - 1
                    while (i >= 0) {
                        allModifiersReverse.add(holderModifiers[i])
                        i--
                    }
                }

                val lazyValue = holder.binding[key] as BoundKeyValue<Value>?
                if (lazyValue != null) {
                    // Unpack the value

                    try {
                        foundValue = lazyValue()
                    } catch (t:Throwable) {
                        try {
                            listener?.keyEvaluationFailedByError(t, true)
                        } catch (suppressed:Throwable) {
                            t.addSuppressed(suppressed)
                        }
                        throw t
                    }

                    holderOfFoundValue = holder
                    foundValueValid = true
                    break@searchForValue
                }
            }

            scope = scope.scopeParent
        }

        if (foundValueValid) {
            @Suppress("UNCHECKED_CAST")
            var result = foundValue as Value

            // Apply modifiers
            try {
                for (i in allModifiersReverse.indices.reversed()) {
                    result = allModifiersReverse[i].invoke(this, result)
                }
            } catch (t:Throwable) {
                try {
                    listener?.keyEvaluationFailedByError(t, false)
                } catch (suppressed:Throwable) {
                    t.addSuppressed(suppressed)
                }
                throw t
            }

            // Store in cache
            if (key.cached) {
                if (scope != null) {
                    putCached(key, result)
                } // else the value is default value which does not need to be cached
            }

            listener?.keyEvaluationSucceeded(scope, holderOfFoundValue, result)
            return result
        }

        if (useOtherwise) {
            listener?.keyEvaluationFailedByNoBinding(true, otherwise)
            return otherwise
        } else {
            listener?.keyEvaluationFailedByNoBinding(false, null)
            throw WemiException.KeyNotAssignedException(key, this@Scope)
        }
    }

    /** Return the value bound to this wemi.key in this scope.
     * Throws exception if no value set. */
    fun <Value> Key<Value>.get(): Value {
        @Suppress("UNCHECKED_CAST")
        return getKeyValue(this, null, false) as Value
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns [unset] if no value set. */
    fun <Value : Else, Else> Key<Value>.getOrElse(unset: Else): Else {
        return getKeyValue(this, unset, true)
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns `null` if no value set. */
    fun <Value> Key<Value>.getOrNull(): Value? {
        return getKeyValue(this, null, true)
    }

    private fun <Value> getCached(key: Key<Value>): Value? {
        synchronized(this) {
            val valueCache = this.valueCache ?: return null
            @Suppress("UNCHECKED_CAST")
            return valueCache[key] as Value
        }
    }

    private fun <Value> putCached(key: Key<Value>, value: Value) {
        synchronized(this) {
            val maybeNullCache = valueCache
            val cache: MutableMap<Key<*>, Any?>
            if (maybeNullCache == null) {
                cache = mutableMapOf()
                this.valueCache = cache
            } else {
                cache = maybeNullCache
            }

            cache.put(key, value)
        }
    }

    /**
     * Forget cached values stored in this and descendant caches.
     *
     * @return amount of values forgotten
     * @see Key.cached
     */
    internal fun cleanCache(): Int {
        synchronized(this) {
            val valueCache = this.valueCache
            this.valueCache = null
            return (valueCache?.size ?: 0) + configurationScopeCache.values.sumBy { it.cleanCache() }
        }
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
}

private val LOG: Logger = LoggerFactory.getLogger("BindingHolder")

/**
 * Holds [Key] value bindings (through [BoundKeyValue]),
 * key modifiers (through [BoundKeyValueModifier]),
 * and [ConfigurationExtension] extensions (through [ConfigurationExtension]).
 *
 * Also provides ways to set them during the object's initialization.
 * After the initialization, the holder is locked and no further modifications are allowed.
 *
 * [BindingHolder] instances form building elements of a [Scope].
 */
sealed class BindingHolder {

    internal val binding = HashMap<Key<*>, BoundKeyValue<Any?>>()
    internal val modifierBindings = HashMap<Key<*>, ArrayList<BoundKeyValueModifier<Any?>>>()
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
     * If the key already has [BoundKeyValue] bound in this scope, it will be replaced.
     *
     * @see modify
     */
    infix fun <Value> Key<Value>.set(value: BoundKeyValue<Value>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        val old = binding.put(this as Key<Any>, value as BoundKeyValue<Any?>)
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
     * First modifier will receive as an argument result of the [BoundKeyValue], second the result of first, and so on.
     * Result of last modifier will be then used as a definitive result of the key query.
     *
     * @param valueModifier to be added
     * @see set
     */
    infix fun <Value> Key<Value>.modify(valueModifier: BoundKeyValueModifier<Value>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        val modifiers = modifierBindings.getOrPut(this as Key<Any>) { ArrayList() } as ArrayList<BoundKeyValueModifier<*>>
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
    /**
     * Add a modifier that will add the result of [additionalValue] to the resulting collection.
     *
     * @see modify
     */
    inline infix fun <Value> Key<Collection<Value>>.add(crossinline additionalValue: BoundKeyValue<Value>) {
        this.modify { collection: Collection<Value> ->
            //TODO Optimize?
            collection + additionalValue()
        }
    }

    /**
     * Add a modifier that will remove the result of [valueToRemove] from the resulting collection.
     *
     * @see modify
     */
    inline infix fun <Value> Key<Collection<Value>>.remove(crossinline valueToRemove: BoundKeyValue<Value>) {
        this.modify { collection: Collection<Value> ->
            collection - valueToRemove()
        }
    }
    //endregion

    //region CompilerFlags utility methods
    /**
     * @see BindingHolder.plusAssign
     * @see BindingHolder.minusAssign
     */
    operator fun <Type> Key<CompilerFlags>.get(flag: CompilerFlag<Collection<Type>>): CompilerFlagKeySetting<Collection<Type>> {
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
    operator fun <Type> Key<CompilerFlags>.set(flag: CompilerFlag<Type>, value: BoundKeyValue<Type>) {
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
    operator fun <Type> CompilerFlagKeySetting<Collection<Type>>.plusAssign(value: Type) {
        key.modify { flags: CompilerFlags ->
            flags[flag].let {
                flags[flag] = if (it == null) listOf(value) else it + value
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
    operator fun <Type> CompilerFlagKeySetting<Collection<Type>>.minusAssign(value: Type) {
        key.modify { flags: CompilerFlags ->
            flags[flag]?.let {
                flags[flag] = it - value
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
}

/**
 * @see useKeyEvaluationListener
 */
@Volatile
private var activeKeyEvaluationListener:WemiKeyEvaluationListener? = null

/**
 * Execute [action] with [listener] set to listen to any key evaluations that are done during this time.
 *
 * Only one listener may be active at any time.
 */
fun <Result>useKeyEvaluationListener(listener: WemiKeyEvaluationListener, action:()->Result):Result {
    if (activeKeyEvaluationListener != null) {
        throw WemiException("Failed to apply KeyEvaluationListener, someone already has listener applied")
    }
    try {
        activeKeyEvaluationListener = listener
        return action()
    } finally {
        assert(activeKeyEvaluationListener === listener) { "Someone has applied different listener during action()!" }
        activeKeyEvaluationListener = null
    }
}

/**
 * Called with details about key evaluation.
 * Useful for closer inspection of key evaluation.
 *
 * Keys are evaluated in a tree, the currently evaluated key is on a stack.
 * @see keyEvaluationStarted for more information
 */
interface WemiKeyEvaluationListener {
    /**
     * Evaluation of a key has started.
     *
     * This will be always ended with [keyEvaluationFailedByNoBinding], [keyEvaluationFailedByError] or
     * with [keyEvaluationSucceeded] call. Between those, [keyEvaluationHasModifiers] can be called
     * and more [keyEvaluationStarted]-[keyEvaluationSucceeded]/Failed pairs can be nested, even recursively.
     *
     * This captures the calling stack of key evaluation.
     *
     * @param fromScope in which scope is the binding being searched from
     * @param key that is being evaluated
     */
    fun keyEvaluationStarted(fromScope: Scope, key: Key<*>)

    /**
     * Called when evaluation of key on top of the key evaluation stack will use some modifiers, if it succeeds.
     *
     * @param modifierFromScope in which scope the modifier has been found
     * @param modifierFromHolder in which holder inside the [modifierFromScope] the modifier has been found
     * @param amount of modifiers added from this scope-holder
     */
    fun keyEvaluationHasModifiers(modifierFromScope: Scope, modifierFromHolder:BindingHolder, amount:Int)

    /**
     * Evaluation of key on top of key evaluation stack has been successful.
     *
     * @param bindingFoundInScope scope in which the binding of this key has been found, null if default value
     * @param bindingFoundInHolder holder in [bindingFoundInScope] in which the key binding has been found (null if from cache)
     * @param result that has been used, may be null if caller considers null
     */
    fun <Value>keyEvaluationSucceeded(bindingFoundInScope:Scope?,
                                      bindingFoundInHolder: BindingHolder?,
                                      result:Value)

    /**
     * Evaluation of key on top of key evaluation stack has failed, because the key has no binding, nor default value.
     *
     * @param withAlternative user supplied alternative will be used if true (passed in [alternativeResult]),
     *                          [wemi.WemiException.KeyNotAssignedException] will be thrown if false
     */
    fun keyEvaluationFailedByNoBinding(withAlternative:Boolean, alternativeResult:Any?)

    /**
     * Evaluation of key or one of the modifiers has thrown an exception.
     * This means that the key evaluation has failed and the exception will be thrown.
     *
     * This is not invoked when the exception is [wemi.WemiException.KeyNotAssignedException] thrown by the key
     * evaluation system itself. [keyEvaluationFailedByNoBinding] will be called for that.
     *
     * @param exception that was thrown
     * @param fromKey if true, the evaluation of key threw the exception, if false it was one of the modifiers
     */
    fun keyEvaluationFailedByError(exception:Throwable, fromKey:Boolean)
}

