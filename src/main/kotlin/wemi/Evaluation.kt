package wemi

import java.io.Closeable

/**
 *
 */

internal var globalTickTime = 0

internal val NO_BINDING_MODIFIERS = emptyArray<BoundKeyValueModifier<*>>()
internal val NO_CONFIGURATIONS = emptyArray<Configuration>()

/** @param value may be null when default key value is used. */
internal class Binding<T>(val key:Key<T>, val value:BoundKeyValue<T>?, val modifiers:Array<BoundKeyValueModifier<T>>) {

    internal val dependsOn = ArrayList<Pair<Array<Configuration>, Binding<*>>>()

    internal var lastEvaluated:Int = -1
    /* Evaluating key repeatedly with different input will trash the cache.
    It is not expected to happen, so we do not optimize for it.
    (Besides, changing values of any other dependency key will do the same) */
    internal var lastEvaluatedWithInput:Array<out Pair<String, String>> = NO_INPUT
    internal var lastEvaluatedTo:T? = null

    internal fun isFresh(forInput:Array<out Pair<String, String>>):Boolean {
        if (lastEvaluated == -1) {
            return false
        }
        if (lastEvaluated == globalTickTime) {
            return true
        }
        if (!lastEvaluatedWithInput.contentEquals(forInput)) {
            return false
        }
        if (value is Expirable && value.isExpired() || modifiers.any { it is Expirable && it.isExpired() }) {
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

/** Use this on [BoundKeyValue] or [BoundKeyValueModifier] to specify that this value can expire. */
internal interface Expirable {
    // TODO(jp): This will probably need some extra info here
    /** Called before the already computed value is reused, to check if it can be, or if it has to be recomputed. */
    fun isExpired():Boolean
}

/**
 * Evaluating of [Key]s is possible only inside [EvalScope].
 *
 * Internally, this tracks which [Key]s were used during the evaluation.
 * This information is then used to properly invalidate caches when any of the used keys expire through [Expirable].
 * If none of the used keys expires, the cache is valid forever.
 */
@WemiDsl
class EvalScope @PublishedApi internal constructor(
        @PublishedApi internal val scope:Scope,
        @PublishedApi internal val configurationPrefix:Array<Configuration>,
        @PublishedApi internal val usedBindings: ArrayList<Pair<Array<Configuration>, Binding<*>>>,
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
    @Suppress("unused")
    inline fun <Result> EvalScope.using(vararg configurations: Configuration, action: EvalScope.() -> Result): Result {
        ensureNotClosed()
        var scope = scope
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return EvalScope(scope, configurationPrefix + configurations, usedBindings, input).use { it.action() }
    }

    /** Run the [action] in a scope, which is created by layering [configurations] over this [Scope]. */
    @Suppress("unused")
    inline fun <Result> EvalScope.using(configurations: Collection<Configuration>, action: EvalScope.() -> Result): Result {
        ensureNotClosed()
        var scope = scope
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return EvalScope(scope, configurationPrefix + configurations, usedBindings, input).use { it.action() }
    }

    /** Run the [action] in a scope, which is created by layering [configuration] over this [Scope]. */
    @Suppress("unused")
    inline fun <Result> EvalScope.using(configuration: Configuration, action: EvalScope.() -> Result): Result {
        ensureNotClosed()
        return EvalScope(scope.scopeFor(configuration), configurationPrefix + configuration, usedBindings, input).use { it.action() }
    }

    private fun <Value : Output, Output> getKeyValue(key: Key<Value>, otherwise: Output, useOtherwise: Boolean, input:Array<out Pair<String, String>>): Output {
        ensureNotClosed()
        val listener = activeKeyEvaluationListener
        listener?.keyEvaluationStarted(this.scope, key)

        val binding = scope.getKeyBinding(key)
                ?: // Use default binding
                if (useOtherwise) {
                    listener?.keyEvaluationFailedByNoBinding(true, otherwise)
                    return otherwise
                } else {
                    listener?.keyEvaluationFailedByNoBinding(false, null)
                    throw WemiException.KeyNotAssignedException(key, this@EvalScope.scope)
                }

        val result:Value = if (binding.isFresh(input)) {
            @Suppress("UNCHECKED_CAST")
            binding.lastEvaluatedTo as Value
        } else {
            val newDependsOn = ArrayList<Pair<Array<Configuration>, Binding<*>>>()
            val result:Value =
            EvalScope(scope, NO_CONFIGURATIONS, newDependsOn, input).use { evalScope ->
                val boundValue = binding.value
                var result = if (boundValue == null) {
                    @Suppress("UNCHECKED_CAST")
                    binding.key.defaultValue as Value
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

            binding.lastEvaluated = globalTickTime
            binding.lastEvaluatedWithInput = input
            binding.lastEvaluatedTo = result

            binding.dependsOn.clear()
            binding.dependsOn.addAll(newDependsOn)

            scope.keyBindingCache[key] = binding
            result
        }

        // Done
        listener?.keyEvaluationSucceeded(key, scope, /*holderOfFoundValue*/null, result)// TODO(jp): /**/
        return result
    }

    /** Return the value bound to this wemi.key in this scope.
     * Throws exception if no value set. */
    fun <Value> Key<Value>.get(): Value {
        @Suppress("UNCHECKED_CAST")
        return getKeyValue(this, null, false, NO_INPUT) as Value
    }

    /** Same as [get], but with ability to specify [input] of the task. */
    fun <Value> Key<Value>.get(vararg input:Pair<String, String>): Value {
        @Suppress("UNCHECKED_CAST")
        return getKeyValue(this, null, false, input) as Value
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns [unset] if no value set. */
    fun <Value : Else, Else> Key<Value>.getOrElse(unset: Else): Else {
        return getKeyValue(this, unset, true, NO_INPUT)
    }

    /** Same as [getOrElse], but with ability to specify [input]. See [get]. */
    fun <Value : Else, Else> Key<Value>.getOrElse(unset: Else, vararg input:Pair<String, String>): Else {
        return getKeyValue(this, unset, true, input)
    }
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
     * @see FEATURE_WRITTEN_TO_CACHE
     */
    fun keyEvaluationFeature(feature:String) {}

    /**
     * Evaluation of key on top of key evaluation stack has been successful.
     *
     * @param key that just finished executing, same as the one from [keyEvaluationStarted]
     * @param bindingFoundInScope scope in which the binding of this key has been found, null if default value
     * @param bindingFoundInHolder holder in [bindingFoundInScope] in which the key binding has been found, null if default value
     * @param result that has been used, may be null if caller considers null valid
     */
    fun <Value>keyEvaluationSucceeded(key: Key<Value>,
                                      bindingFoundInScope: Scope?,
                                      bindingFoundInHolder: BindingHolder?,
                                      result: Value) {}

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
        /** [keyEvaluationFeature] to signify that this value has not been found in cache, but was stored there for later use */
        const val FEATURE_WRITTEN_TO_CACHE = "to cache"
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

    override fun <Value> keyEvaluationSucceeded(key: Key<Value>, bindingFoundInScope: Scope?, bindingFoundInHolder: BindingHolder?, result: Value) {
        first.keyEvaluationSucceeded(key, bindingFoundInScope, bindingFoundInHolder, result)
        second.keyEvaluationSucceeded(key, bindingFoundInScope, bindingFoundInHolder, result)
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


/*
When evaluating a key:

Binding is a collection of:
- BoundKeyValue & BoundKeyValueModifiers
    - both specify "isStale()" function, all those are evaluated and if none is stale, binding is not stale
- Cached result value, if any
- Datastamp of cached result value creation
- collection of bindings which got resolved during last evaluation and are checked for freshness
    - Map<(Configuration[], Key), Binding>
- isRelevantIn(Scope)
- isStale()
- isSuperseded()
- isValid() ?

Single binding per Key is stored per Scope.
Multiple scopes may share single binding.
Bindings hold references to other bindings that were used previously.

??? Binding is immutable in its description and may become evaluated.
When such binding is no longer needed, it will be discarded, so that other bindings may detect it as discarded and discard themselves.

Binding can detect when it is stale and when it is superseded.
Stale = task itself has decided to recalculate
Superseded = when previously known dependent bindings are stale or not relevant
Valid = not stale and not superseded

Binding can check if it is relevant in some other scope, by checking if all of its children resolve to the same bindings.

1. Resolve its binding currentBinding (or check for its validity)
    1. check for parent/children scopes if they contain relevant bindings, if so, use them as own
    1. otherwise use own binding
    1. if that binding is not valid, create new binding
    1. store that binding for later and other scopes
2. If there is oldBinding, check if equal
    2. equal: use oldBinding as binding
    2. not equal: use currentBinding as binding, becoming oldBinding
3.


 */