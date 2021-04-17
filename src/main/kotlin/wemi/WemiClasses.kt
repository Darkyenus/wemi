@file:Suppress("MemberVisibilityCanPrivate", "MemberVisibilityCanBePrivate")

package wemi

import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import wemi.boot.MachineReadableFormatter
import wemi.collections.toMutable
import wemi.compile.CompilerFlag
import wemi.compile.CompilerFlags
import wemi.util.Color
import wemi.util.Format
import wemi.util.JsonWritable
import wemi.util.WithDescriptiveString
import wemi.util.addAllReversed
import wemi.util.field
import wemi.util.format
import wemi.util.name
import wemi.util.writeObject
import wemi.util.writeValue
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

typealias InputKey = String
typealias InputKeyDescription = String

typealias project = ProjectDelegate
typealias key<T> = KeyDelegate<T>
typealias configuration = ConfigurationDelegate
typealias archetype = ArchetypeDelegate
typealias command<T> = CommandDelegate<T>

/** Generate newline-ended pretty (with ANSI formatting) representation of the object.
 * Of the object is a collection which makes sense truncated, show only `maxElements` amount of elements. */
typealias PrettyPrinter<V> = ((V, maxElements:Int) -> CharSequence)

/** An axis identifier. See [Configuration.axis] for more info. */
class Axis(val name:String) {
    internal val id:Int = nextAxisID.getAndIncrement()
}

private val nextAxisID = AtomicInteger(0)

/** Key which can have value of type [V] assigned, through [Project] or [Configuration]. */
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
         * Called when the key is evaluated in CLI top-level.
         */
        internal val prettyPrinter: PrettyPrinter<V>?,
        /**
         * Optional function that will print the result of this key's evaluation
         * in a specified machine-readable format.
         *
         * Called when the key is evaluated in top-level in machine-readable output mode.
         */
        internal val machineReadableFormatter:MachineReadableFormatter<V>?) : WithDescriptiveString, JsonWritable, Comparable<Key<*>> {

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

private val nextConfigDimensionID = AtomicInteger(0)

/**
 * Configuration is a layer of bindings that can be added to the [Scope].
 *
 * In [Scope], each configuration [axis] can appear only once - layering in new configurations from the same
 * axis will replace the previous configuration.
 *
 * @param name of the configuration. Specified by the variable name this configuration was declared at.
 * @param description to be displayed in the CLI as help
 * @param axis There can be only one scope part per axis in a scope. Extending a scope by adding a new part
 * of the same axis that already exists in the scope will remove the old part. Null is the same as returning
 * a unique axis.
 * @see BindingHolder for info about how the values are bound
 */
@WemiDsl
class Configuration internal constructor(val name: String,
                                         val description: String,
                                         val axis: Axis)
    : ExtendableBindingHolder(), JsonWritable {

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
 * Holds information about configuration extensions made by [ExtendableBindingHolder.extend].
 * Values bound to this take precedence over the values [extending].
 *
 * @param extending which configuration is being extended by this extension (for debugging)
 * @param from which this extension has been created (for debugging)
 */
@WemiDsl
class BindingHolderExtension internal constructor(
        @Suppress("MemberVisibilityCanBePrivate")
        val extending: ExtendableBindingHolder,
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
    : ExtendableBindingHolder(), WithDescriptiveString, JsonWritable {

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

    /** Archetypes and their parents, from lowest archetype parent up to the project. */
    internal val baseHolders:Array<ExtendableBindingHolder> = run {
        val result = ArrayList<ExtendableBindingHolder>()
        result.add(this@Project)

        // Iterate through archetypes, most important first
        var i = archetypes.lastIndex
        while (i >= 0) {
            var archetype = archetypes[i--]

            while (true) {
                result.add(archetype)
                archetype = archetype.parent ?: break
            }
        }

        val size = result.size
        Array(size) { result[size - 1 - it] }
    }

    private fun createScope(configurations:List<Configuration>, command:CommandBindingHolder?):Scope {
        @Suppress("UNCHECKED_CAST")
        val rawHolders:Array<ExtendableBindingHolder> = run {
            val baseHolders = baseHolders
            val baseHoldersSize = baseHolders.size
            val rawHolders = arrayOfNulls<ExtendableBindingHolder>(baseHoldersSize + configurations.size + (if (command == null) 0 else 1))
            System.arraycopy(baseHolders, 0, rawHolders, 0, baseHoldersSize)
            for (i in configurations.indices) {
                rawHolders[baseHoldersSize + i] = configurations[i]
            }
            if (command != null) {
                rawHolders[rawHolders.lastIndex] = command
            }
            rawHolders as Array<ExtendableBindingHolder>
        }

        // rawHolders now contains all configurations without any extensions, in natural order (archetypes, project, configurations)

        // copy rawHolders to holders, this time with extensions
        val holders = ArrayList<BindingHolder>(rawHolders.size * 2)
        for (holder in rawHolders) {
            holders.add(holder)
            for (extendingHolder in rawHolders) {
                if (extendingHolder === holder) {
                    continue
                }
                val extension = extendingHolder.extensions[holder] ?: continue
                holders.add(extension)
            }
        }

        return Scope(this, configurations, command, holders)
    }

    internal val scopeCache = HashMap<List<Configuration>, Scope>()

    /** Create base [Scope] in which evaluation tree for this [Project] can start. */
    @PublishedApi
    internal fun scopeFor(parts:List<Configuration>, command:CommandBindingHolder?):Scope {
        // Filter it
        val filteredParts = ArrayList<Configuration>(parts.size)
        val usedAxes = BitSet()
        for (i in parts.indices.reversed()) {
            val part = parts[i]
            val axis = part.axis
            if (!usedAxes[axis.id]) {
                filteredParts.add(part)
                usedAxes[axis.id] = true
            }
        }
        filteredParts.reverse()

        if (command != null) {
            return createScope(filteredParts, command)
        }

        return scopeCache.getOrPut(filteredParts) { createScope(filteredParts, null) }
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
    internal inline fun <Result> evaluate(listener:EvaluationListener?, vararg configurations:Configuration, command:CommandBindingHolder? = null, action:EvalScope.()->Result):Result {
        try {
            evaluateLock()
            return EvalScope(this.scopeFor(configurations.toList(), command), ArrayList(), ArrayList(), listener).run(action)
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
 * @see wemi.archetypes for more info about this concept
 */
@WemiDsl
class Archetype internal constructor(val name: String, val parent:Archetype?) : ExtendableBindingHolder() {

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

/** Holds transient bindings for a single command evaluation. */
class CommandBindingHolder internal constructor(
    private val project: Project,
    private val configurations: Array<Configuration>,
    internal val input: Array<Pair<String, String>>,
    private val listener: EvaluationListener?
) : ExtendableBindingHolder() {

    fun <V> evaluate(value:Value<V>):V {
        return project.evaluate(listener, *configurations, command = this, action = value)
    }

    fun <V> evaluate(vararg configurations: Configuration, value:Value<V>):V {
        return project.evaluate(listener, *this.configurations + configurations, command = this, action = value)
    }

    override fun toDescriptiveAnsiString(): String {
        val sb = StringBuilder()
        sb.append("CommandBindingHolder").format(Color.White).append("(")
        input.joinTo(sb, ", ")
        sb.format(Color.White).append(")").format()
        return sb.toString()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("CommandBindingHolder(")
        input.joinTo(sb, ", ")
        sb.append(")")
        return sb.toString()
    }
}

/** A command is similar to a [Key] with a fixed binding, but it can be invoked only from the CLI or from another commands,
 * and it accepts textual input which can be used to modify the execution scope.
 *
 * Functionality should be generally implemented through standard [Key]s and [Command]s should
 * only serve as a more convenient/interactive UI for them.
 *
 * Unlike [Key]s, [Command]s cannot be rebound. */
class Command<T> internal constructor(
    val name: String,
    val description: String,
    internal val execute:CommandBindingHolder.() -> T,
    /**
     * Optional function that can convert the result of this command's evaluation to a more readable
     * or more informative string.
     */
    internal val prettyPrinter: PrettyPrinter<T>?,
    /**
     * Optional function that will print the result of this command's evaluation
     * in a specified machine-readable format.
     *
     * Called when the command is evaluated in top-level in machine-readable output mode.
     */
    internal val machineReadableFormatter:MachineReadableFormatter<T>?) : WithDescriptiveString {

    override fun toDescriptiveAnsiString(): String = format(name, format = Format.Bold, foreground = Color.Blue).toString()

    override fun toString(): String = name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Command<*>) return false

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}

/**
 * Defines a part of scope in the scope linked-list structure.
 * Scope allows to query the values of bound [Key]s.
 * Each scope is internally formed by an ordered list of [BindingHolder]s.
 *
 * @param bindingHolders list of holders contributing to the scope's holder stack. Most significant holders last.
 */
class Scope internal constructor(
    val project:Project,
    val configurations:List<Configuration>,
    @PublishedApi internal val command:CommandBindingHolder?,
    internal val bindingHolders:List<BindingHolder>) {

    private val configurationScopeCache: MutableMap<Configuration, Scope> = HashMap()

    /** When key is evaluated, it needs a [Binding].
     * This cache contains only already at-least once evaluated bindings. */
    internal val keyBindingCache:MutableMap<Key<*>, Binding<*>> = HashMap()

    private fun <T> createElementaryKeyBinding(key:Key<T>, listener:EvaluationListener?):Binding<T>? {
        val boundValue: Value<T>?
        val boundValueOriginHolder: BindingHolder?
        val allModifiersReverse = ArrayList<ValueModifier<T>>()

        searchForBoundValue@do {
            // Retrieve the holder
            for (holderI in bindingHolders.indices.reversed()/* reverse iteration without allocation */) {
                val holder = bindingHolders[holderI]
                val holderModifiers = holder.modifierBindings[key]
                if (holderModifiers != null && holderModifiers.isNotEmpty()) {
                    listener?.keyEvaluationHasModifiers(holder, holderModifiers.size)
                    @Suppress("UNCHECKED_CAST")
                    allModifiersReverse.addAllReversed(holderModifiers as ArrayList<ValueModifier<T>>)
                }

                val boundValueCandidate = holder.binding[key]
                if (boundValueCandidate != null) {
                    @Suppress("UNCHECKED_CAST")
                    boundValue = boundValueCandidate as Value<T>
                    boundValueOriginHolder = holder
                    break@searchForBoundValue
                }
            }

            if (key.hasDefaultValue) {
                boundValue = null
                boundValueOriginHolder = null
                break@searchForBoundValue
            } else {
                return null
            }

        } while(@Suppress("UNREACHABLE_CODE") false)

        val modifiers:Array<ValueModifier<T>> =
                if (allModifiersReverse.size == 0) {
                    @Suppress("UNCHECKED_CAST")
                    NO_BINDING_MODIFIERS as Array<ValueModifier<T>>
                } else Array(allModifiersReverse.size) {
                    allModifiersReverse[allModifiersReverse.size - it - 1]
                }
        return Binding(key, boundValue, modifiers, boundValueOriginHolder)
    }

    internal fun <T> getKeyBinding(key:Key<T>, listener:EvaluationListener?):Binding<T>? {
        // Put into the cache after successful evaluation
        keyBindingCache[key]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as Binding<T>
        }

        return createElementaryKeyBinding(key, listener)
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

    /** @return scope in the standard syntax, i.e. project/config1:config2: */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(project.name).append('/')
        for (configuration in configurations) {
            sb.append(configuration.name).append(':')
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Scope) return false

        if (project != other.project) return false
        if (configurations != other.configurations) return false

        return true
    }

    override fun hashCode(): Int {
        var result = project.hashCode()
        result = 31 * result + configurations.hashCode()
        return result
    }
}

private val LOG: Logger = LoggerFactory.getLogger("BindingHolder")

sealed class ExtendableBindingHolder : BindingHolder() {
    internal val extensions = HashMap<ExtendableBindingHolder, BindingHolderExtension>()

    /**
     * Extend given configuration so that when it is accessed with this [BindingHolder] in [Scope],
     * given [BindingHolderExtension] will be queried for bindings first.
     *
     * @param bindingHolder to extend, a [Configuration] or an [Archetype]
     * @param initializer that will be executed to populate the configuration
     */
    fun extend(bindingHolder: ExtendableBindingHolder, initializer: BindingHolderExtension.() -> Unit) {
        if (this == bindingHolder) {
            throw WemiException("Extending self is not allowed")
        }
        ensureUnlocked()
        val extension = extensions.getOrPut(bindingHolder) {
            BindingHolderExtension(bindingHolder, this) }
        extension.locked = false
        extension.initializer()
        extension.locked = true
    }
}

/**
 * Holds [Key] value bindings (through [Value]),
 * key modifiers (through [ValueModifier]),
 * and [BindingHolderExtension] extensions (through [BindingHolderExtension]).
 *
 * Also provides ways to set them during the object's initialization.
 * After the initialization, the holder is locked and no further modifications are allowed.
 *
 * [BindingHolder] instances form building elements of a [Scope].
 */
sealed class BindingHolder : WithDescriptiveString {

    internal val binding = HashMap<Key<*>, Value<Any?>>()
    internal val modifierBindings = HashMap<Key<*>, ArrayList<ValueModifier<Any?>>>()
    internal var locked = false

    internal fun ensureUnlocked() {
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
     * Like [set], but the value is static and not dynamically computed.
     *
     * Only for values that should be evaluated only once per whole binding,
     * regardless of Scope. Use only for values with no dependencies on any modifiable input.
     *
     * Example:
     * ```kotlin
     * projectName put "MyProject"
     * ```
     */
    infix fun <V> Key<V>.put(staticValue: V) {
        this.set {
            progressListener?.keyEvaluationFeature("put")
            staticValue
        }
    }

    /**
     * Something between [set] and [put].
     * For values that should be evaluated only once, like in [put], but lazily.
     * Similar to [put] in nature of supported values.
     *
     * Example:
     * ```kotlin
     * heavyResource putLazy { createHeavyResource() }
     * ```
     */
    infix fun <V> Key<V>.putLazy(lazyValue: () -> V) {
        this set object : (EvalScope) -> V {
            private var generator:(()->V)? = lazyValue
            private var cachedValue:V? = null

            override fun invoke(scope: EvalScope): V {
                val generator = this.generator
                val value:V
                @Suppress("UNCHECKED_CAST")
                if (generator != null) {
                    value = generator.invoke()
                    this.cachedValue = value
                    this.generator = null
                    scope.progressListener?.keyEvaluationFeature("first lazy static")
                } else {
                    scope.progressListener?.keyEvaluationFeature("lazy static")
                    value = this.cachedValue as V
                }
                return value
            }
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

    //region Specific utility methods
    /**
     * Modify [CompilerFlags] to set the given compiler [flag] to the given [value]
     * that will be evaluated as if it was a key binding.
     *
     * @see modify
     */
    operator fun <Type> Key<CompilerFlags>.set(flag: CompilerFlag<Type>, value: ValueModifier<Type>) {
        this.modify { flags: CompilerFlags ->
            flags[flag] = value(flags.getOrDefault(flag))
            flags
        }
    }
    //endregion

    /**
     * One line string, using only White foreground for non-important stuff and Bold for important stuff.
     */
    abstract override fun toDescriptiveAnsiString(): String
}
