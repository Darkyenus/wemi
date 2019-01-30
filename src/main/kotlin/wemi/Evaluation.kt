package wemi

import wemi.util.lastModifiedMillis
import java.io.Closeable
import java.nio.file.Path

/** Each external key invocation will increase this value (=tick).
 * Bindings are evaluated only once per tick, regardless of their expiry. */
internal var globalTickTime = 0

internal val NO_BINDING_MODIFIERS = emptyArray<ValueModifier<*>>()
internal val NO_CONFIGURATIONS = emptyArray<Configuration>()
private val ALWAYS_EXPIRED:() -> Boolean = { true }

/** Characterizes found binding of [value] and its [modifiers] for some [key]. */
class Binding<T>(val key:Key<T>,
                 /** May be null when default key value is used. */
                 val value:Value<T>?,
                 val modifiers:Array<ValueModifier<T>>,
                 /** Scope in which [value] was found. Same nullability as [value]. */
                 val valueOriginScope:Scope?,
                 /** BindingHolder in which [value] was found. Same nullability as [value]. */
                 val valueOriginHolder:BindingHolder?) {

    internal val dependsOn = ArrayList<Pair<Array<Configuration>, Binding<*>>>()

    internal var lastEvaluated:Int = -1
    /* Evaluating key repeatedly with different input will trash the cache.
    It is not expected to happen, so we do not optimize for it.
    (Besides, changing values of any other dependency key will do the same) */
    internal var lastEvaluatedWithInput:Array<out Pair<String, String>> = NO_INPUT
    internal var lastEvaluatedTo:T? = null
    internal var lastEvaluationExpirationTriggers = ArrayList<() -> Boolean>()

    internal fun isFresh(forInput:Array<out Pair<String, String>>):Boolean {
        if (lastEvaluated == -1) {
            return false
        }
        if (!lastEvaluatedWithInput.contentEquals(forInput)) {
            return false
        }
        if (lastEvaluated == globalTickTime) {
            return true
        }
        @Suppress("UNCHECKED_CAST")
        if (lastEvaluationExpirationTriggers.any { it() }) {
            return false
        }
        return dependsOn.all { it.second.isFresh(it.second.lastEvaluatedWithInput) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Binding<*>

        if (value != other.value) return false
        if (!modifiers.contentEquals(other.modifiers)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = value?.hashCode() ?: 0
        result = 31 * result + modifiers.contentHashCode()
        return result
    }
}

/**
 * Evaluating of [Key]s is possible only inside [EvalScope].
 *
 * Internally, this tracks which [Key]s were used during the evaluation.
 * This information is then used to properly invalidate caches when any of the used keys expire through [expiresWhen].
 * If none of the used keys expires, the cache is valid forever.
 */
@WemiDsl
class EvalScope @PublishedApi internal constructor(
        @PublishedApi internal val scope:Scope,
        @PublishedApi internal val configurationPrefix:Array<Configuration>,
        @PublishedApi internal val usedBindings: ArrayList<Pair<Array<Configuration>, Binding<*>>>,
        @PublishedApi internal val expirationTriggers: ArrayList<() -> Boolean>,
        @PublishedApi internal val input:Array<out Pair<String, String>>) : Closeable {

    /** Used by the input subsystem. See Input.kt. */
    internal var nextFreeInput = 0

    private var closed = false

    override fun close() {
        closed = true
    }

    /** It is important that EvalScope is not used at random times, but only when it is passed as a receiver.
     * Doing that would invalidate the purpose of the EvalScope, to track the used bindings of the keys. */
    @PublishedApi
    internal fun ensureNotClosed() {
        if (closed) {
            throw WemiException("EvalScope is closed. This can happen when it is used outside of the action it was receiver for.")
        }
    }

    /** Run the [action] in a scope, which is created by layering [configurations] over this [Scope]. */
    inline fun <Result> using(vararg configurations: Configuration, action: EvalScope.() -> Result): Result {
        ensureNotClosed()
        var scope = scope
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return EvalScope(scope, configurationPrefix + configurations, usedBindings, expirationTriggers, input).use { it.action() }
    }

    /** Run the [action] in a scope, which is created by layering [configurations] over this [Scope]. */
    inline fun <Result> using(configurations: Collection<Configuration>, action: EvalScope.() -> Result): Result {
        ensureNotClosed()
        var scope = scope
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return EvalScope(scope, configurationPrefix + configurations, usedBindings, expirationTriggers, input).use { it.action() }
    }

    /** Run the [action] in a scope, which is created by layering [configuration] over this [Scope]. */
    inline fun <Result> using(configuration: Configuration, action: EvalScope.() -> Result): Result {
        ensureNotClosed()
        return EvalScope(scope.scopeFor(configuration), configurationPrefix + configuration, usedBindings, expirationTriggers, input).use { it.action() }
    }

    private fun <V : Output, Output> getKeyValue(key: Key<V>, otherwise: Output, useOtherwise: Boolean, input:Array<out Pair<String, String>>): Output {
        ensureNotClosed()
        val listener = activeKeyEvaluationListener
        listener?.keyEvaluationStarted(this.scope, key)

        val binding = scope.getKeyBinding(key, listener)
                ?: // Use default binding
                if (useOtherwise) {
                    listener?.keyEvaluationFailedByNoBinding(true, otherwise)
                    return otherwise
                } else {
                    listener?.keyEvaluationFailedByNoBinding(false, null)
                    throw WemiException.KeyNotAssignedException(key, this@EvalScope.scope)
                }

        val result:V = if (binding.isFresh(input)) {
            listener?.keyEvaluationFeature(WemiKeyEvaluationListener.FEATURE_READ_FROM_CACHE)
            @Suppress("UNCHECKED_CAST")
            binding.lastEvaluatedTo as V
        } else {
            val newDependsOn = ArrayList<Pair<Array<Configuration>, Binding<*>>>()
            // Following evaluation may add new expiration triggers, so clean the old ones
            binding.lastEvaluationExpirationTriggers.clear()

            val result:V =
            EvalScope(scope, NO_CONFIGURATIONS, newDependsOn, binding.lastEvaluationExpirationTriggers, input).use { evalScope ->
                val boundValue = binding.value
                var result =
                if (boundValue == null) {
                    @Suppress("UNCHECKED_CAST")
                    binding.key.defaultValue as V
                } else try {
                    boundValue(evalScope)
                } catch (t: Throwable) {
                    try {
                        listener?.keyEvaluationFailedByError(t, true)
                    } catch (suppressed: Throwable) {
                        t.addSuppressed(suppressed)
                    }
                    throw t
                }

                for (modifier in binding.modifiers) {
                    try {
                        result = modifier(evalScope, result)
                    } catch (t: Throwable) {
                        try {
                            listener?.keyEvaluationFailedByError(t, false)
                        } catch (suppressed: Throwable) {
                            t.addSuppressed(suppressed)
                        }
                        throw t
                    }
                }
                result
            }

            if (binding.lastEvaluationExpirationTriggers.isNotEmpty()) {
                listener?.keyEvaluationFeature(WemiKeyEvaluationListener.FEATURE_EXPIRATION_TRIGGERS)
            }

            binding.lastEvaluated = globalTickTime
            binding.lastEvaluatedWithInput = input
            binding.lastEvaluatedTo = result

            binding.dependsOn.clear()
            binding.dependsOn.addAll(newDependsOn)

            scope.keyBindingCache[key] = binding
            result
        }

        // Done
        listener?.keyEvaluationSucceeded(binding, result)
        return result
    }

    /** Return the value bound to this wemi.key in this scope.
     * Throws exception if no value set. */
    fun <V> Key<V>.get(): V {
        @Suppress("UNCHECKED_CAST")
        return getKeyValue(this, null, false, NO_INPUT) as V
    }

    /** Same as [get], but with ability to specify [input] of the task. */
    fun <V> Key<V>.get(vararg input:Pair<String, String>): V {
        @Suppress("UNCHECKED_CAST")
        return getKeyValue(this, null, false, input) as V
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns [unset] if no value set. */
    fun <V : Else, Else> Key<V>.getOrElse(unset: Else): Else {
        return getKeyValue(this, unset, true, NO_INPUT)
    }

    /** Same as [getOrElse], but with ability to specify [input]. See [get]. */
    fun <V : Else, Else> Key<V>.getOrElse(unset: Else, vararg input:Pair<String, String>): Else {
        return getKeyValue(this, unset, true, input)
    }

    /** Value that will be eventually returned as a result of this evaluation will additionally expire when [isExpired]
     * returns `true`. */
    fun expiresWhen(isExpired:() -> Boolean) {
        expirationTriggers.add(isExpired)
    }

    /** Returned value will be considered expired immediately and will not be cached
     * (except for the duration of this top-level evaluation).
     * Equivalent to [expiresWhen]`{ true }`. */
    fun expiresNow() {
        expirationTriggers.add(ALWAYS_EXPIRED)
    }
}

/** When the file changes (detected by presence and date modified, not recursive), the result of this evaluation will expire.
 * @see EvalScope.expiresWhen for more details about expiration */
fun EvalScope.expiresWith(file: Path) {
    val time = file.lastModifiedMillis()

    expiresWhen { file.lastModifiedMillis() != time }
}


/** @see useKeyEvaluationListener */
internal var activeKeyEvaluationListener:WemiKeyEvaluationListener? = null
    private set

/** Execute [action] with [listener] set to listen to any key evaluations that are done during this time. */
fun <Result>useKeyEvaluationListener(listener: WemiKeyEvaluationListener, action:()->Result):Result {
    val oldListener = activeKeyEvaluationListener
    val usedListener =
            if (oldListener == null) listener else WemiKeyEvaluationListenerSplitter(oldListener, listener)
    try {
        activeKeyEvaluationListener = usedListener
        return action()
    } finally {
        assert(activeKeyEvaluationListener === usedListener) { "Someone has applied different listener during action()!" }
        activeKeyEvaluationListener = oldListener
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
    fun keyEvaluationStarted(fromScope: Scope, key: Key<*>) {}

    /**
     * Called when evaluation of key on top of the key evaluation stack will use some modifiers, if it succeeds.
     *
     * @param modifierFromScope in which scope the modifier has been found
     * @param modifierFromHolder in which holder inside the [modifierFromScope] the modifier has been found
     * @param amount of modifiers added from this scope-holder
     */
    fun keyEvaluationHasModifiers(modifierFromScope: Scope, modifierFromHolder:BindingHolder, amount:Int) {}

    /**
     * Called when evaluation of key on top of the key evaluation stack used some special feature,
     * such as retrieval from cache.
     *
     * @param feature short uncapitalized human readable description of the feature, for example "from cache"
     * @see FEATURE_READ_FROM_CACHE
     * @see FEATURE_EXPIRATION_TRIGGERS
     */
    fun keyEvaluationFeature(feature:String) {}

    /**
     * Evaluation of key on top of key evaluation stack has been successful.
     *
     * @param binding that was used to fulfill the key evaluation
     * @param result that has been used, may be null if caller considers null valid
     */
    fun <V>keyEvaluationSucceeded(binding: Binding<V>, result: V) {}

    /**
     * Evaluation of key on top of key evaluation stack has failed, because the key has no binding, nor default value.
     *
     * @param withAlternative user supplied alternative will be used if true (passed in [alternativeResult]),
     *                          [wemi.WemiException.KeyNotAssignedException] will be thrown if false
     */
    fun keyEvaluationFailedByNoBinding(withAlternative:Boolean, alternativeResult:Any?) {}

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
    fun keyEvaluationFailedByError(exception:Throwable, fromKey:Boolean) {}

    companion object {
        /** [keyEvaluationFeature] to signify that this value has been read from cache */
        const val FEATURE_READ_FROM_CACHE = "from cache"
        /** [keyEvaluationFeature] to signify that when this value has been cached, it specified explicit expiration triggers */
        const val FEATURE_EXPIRATION_TRIGGERS = "has expiration triggers"
    }
}

private class WemiKeyEvaluationListenerSplitter(
        private val first:WemiKeyEvaluationListener,
        private val second:WemiKeyEvaluationListener) : WemiKeyEvaluationListener {

    override fun keyEvaluationStarted(fromScope: Scope, key: Key<*>) {
        first.keyEvaluationStarted(fromScope, key)
        second.keyEvaluationStarted(fromScope, key)
    }

    override fun keyEvaluationHasModifiers(modifierFromScope: Scope, modifierFromHolder: BindingHolder, amount: Int) {
        first.keyEvaluationHasModifiers(modifierFromScope, modifierFromHolder, amount)
        second.keyEvaluationHasModifiers(modifierFromScope, modifierFromHolder, amount)
    }

    override fun keyEvaluationFeature(feature: String) {
        first.keyEvaluationFeature(feature)
        second.keyEvaluationFeature(feature)
    }

    override fun <V> keyEvaluationSucceeded(binding: Binding<V>, result: V) {
        first.keyEvaluationSucceeded(binding, result)
        second.keyEvaluationSucceeded(binding, result)
    }

    override fun keyEvaluationFailedByNoBinding(withAlternative: Boolean, alternativeResult: Any?) {
        first.keyEvaluationFailedByNoBinding(withAlternative, alternativeResult)
        second.keyEvaluationFailedByNoBinding(withAlternative, alternativeResult)
    }

    override fun keyEvaluationFailedByError(exception: Throwable, fromKey: Boolean) {
        first.keyEvaluationFailedByError(exception, fromKey)
        second.keyEvaluationFailedByError(exception, fromKey)
    }
}