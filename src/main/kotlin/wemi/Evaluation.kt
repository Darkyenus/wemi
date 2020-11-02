package wemi

import wemi.dependency.ProjectDependency
import wemi.util.ALL_EXTENSIONS
import wemi.util.FileSet
import wemi.util.LOCATED_PATH_COMPARATOR_WITH_TOTAL_ORDERING
import wemi.util.LocatedPath
import wemi.util.PATH_COMPARATOR_WITH_TOTAL_ORDERING
import wemi.util.filterByExtension
import wemi.util.lastModifiedMillis
import wemi.util.matchingFiles
import wemi.util.matchingLocatedFiles
import java.io.Closeable
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Each external key invocation will increase this value (=tick).
 * Bindings are evaluated only once per tick, regardless of their expiry. */
internal var globalTickTime = 0

internal val NO_BINDING_MODIFIERS = emptyArray<ValueModifier<*>>()
@PublishedApi
internal val NO_CONFIGURATIONS = emptyArray<Configuration>()
private val ALWAYS_EXPIRED:() -> Boolean = { true }

internal const val LAST_EVALUATED_NEVER = -1
internal const val LAST_EVALUATED_FORCE_EXPIRED = -2

/** Characterizes found binding of [value] and its [modifiers] for some [key]. */
class Binding<T> constructor(val key:Key<T>,
                 /** May be null when default key value is used. */
                 val value:Value<T>?,
                 val modifiers:Array<ValueModifier<T>>,
                 /** BindingHolder in which [value] was found. Same nullability as [value]. */
                 val valueOriginHolder:BindingHolder?) {

    internal val dependsOn = ArrayList<Binding<*>>()

    internal var lastEvaluated:Int = LAST_EVALUATED_NEVER
    /* Evaluating key repeatedly with different input will trash the cache.
    It is not expected to happen, so we do not optimize for it.
    (Besides, changing values of any other dependency key will do the same) */
    internal var lastEvaluatedWithInput:Array<out Pair<String, String>> = NO_INPUT
    internal var lastEvaluatedTo:T? = null
    internal var lastEvaluationExpirationTriggers = ArrayList<() -> Boolean>()

    internal enum class Freshness(val fresh:Boolean, val listenerMessage:String) {
        Fresh(true, "from cache"),
        FreshThisTick(true, "from cache (already evaluated)"),
        DifferentInput(false, "re-evaluated (different input)"),
        FirstEvaluation(false, "first evaluation"),
        ExplicitlyExpired(false, "re-evaluated (explicit expiry)"),
        ExplicitlyForcedToExpire(false, "re-evaluated (same-tick forced expiry)"),
        ChildNotFresh(false, "re-evaluated (child expired)")
    }

    internal fun isFresh(forInput:Array<out Pair<String, String>>):Freshness {
        if (lastEvaluated == LAST_EVALUATED_NEVER) {
            return Freshness.FirstEvaluation
        }
        if (lastEvaluated == LAST_EVALUATED_FORCE_EXPIRED) {
            return Freshness.ExplicitlyForcedToExpire
        }
        if (!lastEvaluatedWithInput.contentEquals(forInput)) {
            return Freshness.DifferentInput
        }
        if (lastEvaluated == globalTickTime) {
            return Freshness.FreshThisTick
        }
        @Suppress("UNCHECKED_CAST")
        if (lastEvaluationExpirationTriggers.any { it() }) {
            return Freshness.ExplicitlyExpired
        }
        for (binding in dependsOn) {
            if (!binding.isFresh(binding.lastEvaluatedWithInput).fresh) {
                return Freshness.ChildNotFresh
            }
        }
        return Freshness.Fresh
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
        @PublishedApi internal val usedBindings: ArrayList<Binding<*>>,
        @PublishedApi internal val expirationTriggers: ArrayList<() -> Boolean>,
        val input:Array<out Pair<String, String>>,
        val progressListener:EvaluationListener?) : Closeable {

    /** Used by the input subsystem. See Input.kt. */
    internal var nextFreeInput = 0

    private var closed = false

    override fun close() {
        closed = true
    }

    /** It is important that EvalScope is not used at random times, but only when it is passed as a receiver.
     * Doing that would invalidate the purpose of the EvalScope, to track the used bindings of the keys. */
    private fun ensureNotClosed() {
        if (closed) {
            throw WemiException("EvalScope is closed. This can happen when it is used outside of the action it was receiver for.")
        }
    }

    @PublishedApi
    internal fun deriveEvalScope(scope:Scope):EvalScope {
        ensureNotClosed()
        return EvalScope(scope, usedBindings, expirationTriggers, input, progressListener)
    }

    /** Run the [action] in a scope, which is created by layering [configurations] over this [Scope]. */
    inline fun <Result> using(vararg configurations: Configuration, action: EvalScope.() -> Result): Result {
        return deriveEvalScope(scope.project.scopeFor(scope.configurations + configurations)).use { it.action() }
    }

    /** Run the [action] in a scope, which is created by replacing the project and layering the configurations on top. */
    inline fun <Result> using(project:Project, vararg configurations:Configuration, action: EvalScope.() -> Result): Result {
        return deriveEvalScope(project.scopeFor(scope.configurations + configurations)).use { it.action() }
    }

    /** Run the [action] in a scope, which is created by replacing the project with [scope]. */
    inline fun <Result> using(projectDep: ProjectDependency, action: EvalScope.() -> Result): Result {
        return deriveEvalScope(projectDep.project.scopeFor(projectDep.configurations.toList() + scope.configurations)).use { it.action() }
    }

    private fun <V : Output, Output> getKeyValue(key: Key<V>, otherwise: Output, useOtherwise: Boolean, input:Array<out Pair<String, String>>): Output {
        ensureNotClosed()
        val listener = progressListener
        listener?.keyEvaluationStarted(scope, key)

        val binding = scope.getKeyBinding(key, listener)
                ?: // Use default binding
                if (useOtherwise) {
                    listener?.keyEvaluationFailedByNoBinding(true, otherwise)
                    return otherwise
                } else {
                    listener?.keyEvaluationFailedByNoBinding(false, null)
                    throw WemiException.KeyNotAssignedException(key, scope)
                }
        // Record we used this key to fill current binding information
        usedBindings.add(binding)

        val bindingFresh = binding.isFresh(input)
        listener?.keyEvaluationFeature(bindingFresh.listenerMessage)
        val result:V = if (bindingFresh.fresh) {
            @Suppress("UNCHECKED_CAST")
            binding.lastEvaluatedTo as V
        } else {
            val newDependsOn = ArrayList<Binding<*>>()
            // Following evaluation may add new expiration triggers, so clean the old ones
            binding.lastEvaluationExpirationTriggers.clear()

            val result:V =
            EvalScope(scope, newDependsOn, binding.lastEvaluationExpirationTriggers, input, listener).use { evalScope ->
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
                listener?.keyEvaluationFeature(EvaluationListener.FEATURE_EXPIRATION_TRIGGERS)
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

    /** Return the value bound to this wemi.key in this scope.
     * Throws exception if no value set. */
    fun <V> Key<V>.get(vararg input:Pair<String, String> = NO_INPUT): V {
        @Suppress("UNCHECKED_CAST")
        return getKeyValue(this, null, false, input) as V
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns [unset] if no value set. */
    fun <V : Else, Else> Key<V>.getOrElse(unset: Else, vararg input:Pair<String, String> = NO_INPUT): Else {
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

    private inline fun <T> ArrayList<T>.longHashCode(hashCode:(T)->Long):Long {
        var result = 1L
        for (i in indices) {
            val path = this[i]
            result = result * 31 + hashCode(path)
        }
        return result
    }

    /** Obtain the contents of the [FileSet] and expire the result if it changes.
     * @see get for value retrieval
     * @see expiresWhen for expiration mechanism
     * @see FileSet.matchingFiles for path list retrieval */
    fun Key<FileSet?>.getPaths(vararg extensions:String = ALL_EXTENSIONS):List<Path> {
        val fileSet = this.get()?.filterByExtension(*extensions) ?: return emptyList()
        val result = ArrayList<Path>(128)
        fileSet.matchingFiles(result)
        result.sortWith(PATH_COMPARATOR_WITH_TOTAL_ORDERING)
        val originalHash = result.longHashCode(Path::lastModifiedMillis)
        expiresWhen {
            val newPaths = ArrayList<Path>(result.size + 32)
            fileSet.matchingFiles(newPaths)
            newPaths.sortWith(PATH_COMPARATOR_WITH_TOTAL_ORDERING)
            val newHash = newPaths.longHashCode(Path::lastModifiedMillis)
            originalHash != newHash
        }
        return result
    }

    /** Obtain the contents of the [FileSet] and expire the result if it changes.
     * @see get for value retrieval
     * @see expiresWhen for expiration mechanism
     * @see FileSet.matchingFiles for path list retrieval */
    fun Key<FileSet?>.getLocatedPaths(vararg extensions:String = ALL_EXTENSIONS):List<LocatedPath> {
        val fileSet = this.get()?.filterByExtension(*extensions) ?: return emptyList()
        val result = ArrayList<LocatedPath>(128)
        fileSet.matchingLocatedFiles(result)
        result.sortWith(LOCATED_PATH_COMPARATOR_WITH_TOTAL_ORDERING)
        val originalHash = result.longHashCode { it.file.lastModifiedMillis() }
        expiresWhen {
            val newPaths = ArrayList<LocatedPath>(result.size + 32)
            fileSet.matchingLocatedFiles(newPaths)
            newPaths.sortWith(LOCATED_PATH_COMPARATOR_WITH_TOTAL_ORDERING)
            val newHash = newPaths.longHashCode { it.file.lastModifiedMillis() }
            originalHash != newHash
        }
        return result
    }

    /** Force the binding of this key in this scope (if any exists) to expire, this same tick.
     * This can be used to evaluate the same key twice in the same tick.
     * NOTE: DO NOT USE, UNLESS YOU KNOW WHAT ARE YOU DOING
     * (It probably won't break, but there are most likely more adequate tools for the job)
     * @param upTo along with this key, also force expire every key which leads up to the invocation of this one */
    fun Key<*>.forceExpireNow(upTo:Key<*>?) {
        val binding = scope.keyBindingCache[this] ?: return
        binding.lastEvaluated = LAST_EVALUATED_FORCE_EXPIRED
        upTo ?: return

        fun isOnPathUpTo(binding:Binding<*>):Boolean {
            if (binding.key == upTo) {
                binding.lastEvaluated = LAST_EVALUATED_FORCE_EXPIRED
                return true
            }
            var result = false
            for (nextBinding in binding.dependsOn) {
                if (isOnPathUpTo(nextBinding)) {
                    binding.lastEvaluated = LAST_EVALUATED_FORCE_EXPIRED
                    result = true
                }
            }
            return result
        }
        isOnPathUpTo(binding)
    }

    override fun toString(): String {
        return "EvalScope($scope, input=${input.contentToString()})"
    }
}

/** When the file changes (detected by presence and date modified, not recursive), the result of this evaluation will expire.
 * @see EvalScope.expiresWhen for more details about expiration */
fun EvalScope.expiresWith(file: Path) {
    val time = file.lastModifiedMillis()

    expiresWhen { file.lastModifiedMillis() != time }
}

/** Listener watching the progress of some nested activities. */
interface ActivityListener {
    /** Begin some internal [activity]. Must be balanced with [endActivity]. */
    fun beginActivity(activity: String) {}

    /** This activity concerns some data that is being downloaded.
     * Notify user about the progress of this processing.
     * Implicitly completed by end of [beginActivity]-[endActivity] block.
     *
     * @param bytes processed so far
     * @param totalBytes to be processed in total. 0 if unknown.
     * @param durationNs how long in nanoseconds did it took to process this far */
    fun activityDownloadProgress(bytes:Long, totalBytes:Long, durationNs:Long) {}

    /** End activity started by [beginActivity] or by [beginParallelActivity] of the parent listener. */
    fun endActivity() {}

    /** Begin a new activity stack, which may proceed independently of other activities.
     * The parallel's activity [endActivity] must be called before the end of this
     * [ActivityListener]'s current activity ends, but independently of any nested activities.
     * [beginParallelActivity] and [endActivity] are NOT thread safe.
     * @return thread safe alternative parallel listener or null if this feature is not supported. */
    fun beginParallelActivity(activity:String): ActivityListener? = null
}

/** Submit a [task] onto the [ForkJoinPool] and wrap it into a parallel activity ([ActivityListener.beginParallelActivity]).
 * The returned future MUST be manipulated only on the thread which submitted it
 * and the result MUST be obtained (interrupted or timed-out get does not count) or the future MUST be cancelled. */
fun <T> ForkJoinPool.submit(task: (listener:ActivityListener?) -> T, listener:ActivityListener?, parallelActivityName:String): Future<T> {
    var activity = listener?.beginParallelActivity(parallelActivityName)
    val starterThread = Thread.currentThread()
    val future = submit(Callable { return@Callable task(activity) })
    return object : Future<T> {

        fun finish() {
            assert(Thread.currentThread() == starterThread)
            activity?.endActivity()
            activity = null
        }

        override fun isDone(): Boolean = future.isDone

        override fun get(): T {
            var finish = true
            try {
                return future.get()
            } catch (t: InterruptedException) {
                finish = false
                throw t
            } finally {
                if (finish) {
                    finish()
                }
            }
        }

        override fun get(timeout: Long, unit: TimeUnit): T {
            var finish = true
            try {
                return future.get(timeout, unit)
            } catch (t: TimeoutException) {
                finish = false
                throw t
            } catch (t: InterruptedException) {
                finish = false
                throw t
            } finally {
                if (finish) {
                    finish()
                }
            }
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            try {
                return future.cancel(mayInterruptIfRunning)
            } finally {
                finish()
            }
        }

        override fun isCancelled(): Boolean = future.isCancelled
    }
}

/**
 * Called with details about key evaluation.
 * Useful for closer inspection of key evaluation.
 *
 * Keys are evaluated in a tree, the currently evaluated key is on a stack.
 * @see keyEvaluationStarted for more information
 */
interface EvaluationListener : ActivityListener {
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
     * @param modifierFromHolder in which holder the modifier has been found
     * @param amount of modifiers added from this scope-holder
     */
    fun keyEvaluationHasModifiers(modifierFromHolder: BindingHolder, amount: Int) {}

    /**
     * Called when evaluation of key on top of the key evaluation stack used some special feature,
     * such as retrieval from cache.
     *
     * @param feature short uncapitalized human readable description of the feature, for example "from cache"
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
        /** [keyEvaluationFeature] to signify that when this value has been cached, it specified explicit expiration triggers */
        const val FEATURE_EXPIRATION_TRIGGERS = "has expiration triggers"

        operator fun EvaluationListener?.plus(second:EvaluationListener?):EvaluationListener? {
            if (this == null || second == null) {
                return this ?: second
            }

            val first = this

            return object : EvaluationListener {

                override fun keyEvaluationStarted(fromScope: Scope, key: Key<*>) {
                    first.keyEvaluationStarted(fromScope, key)
                    second.keyEvaluationStarted(fromScope, key)
                }

                override fun keyEvaluationHasModifiers(modifierFromHolder: BindingHolder, amount: Int) {
                    first.keyEvaluationHasModifiers(modifierFromHolder, amount)
                    second.keyEvaluationHasModifiers(modifierFromHolder, amount)
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

                override fun beginActivity(activity: String) {
                    first.beginActivity(activity)
                    second.beginActivity(activity)
                }

                override fun activityDownloadProgress(bytes: Long, totalBytes: Long, durationNs:Long) {
                    first.activityDownloadProgress(bytes, totalBytes, durationNs)
                    second.activityDownloadProgress(bytes, totalBytes, durationNs)
                }

                override fun endActivity() {
                    first.endActivity()
                    second.endActivity()
                }

                override fun beginParallelActivity(activity: String): ActivityListener? {
                    return splitBeginParallelActivity(activity, first, second)
                }
            }
        }
    }
}

private fun splitBeginParallelActivity(activity:String, first:ActivityListener, second:ActivityListener):ActivityListener? {
    val firstFork = first.beginParallelActivity(activity)
    val secondFork = second.beginParallelActivity(activity)
    if (firstFork == null) {
        return secondFork
    } else if (secondFork == null) {
        return firstFork
    } else {
        return SplitForkedActivityListener(firstFork, secondFork)
    }
}

private class SplitForkedActivityListener(
        private val firstFork:ActivityListener,
        private val secondFork:ActivityListener) : ActivityListener {

    override fun beginActivity(activity: String) {
        firstFork.beginActivity(activity)
        secondFork.beginActivity(activity)
    }

    override fun activityDownloadProgress(bytes: Long, totalBytes: Long, durationNs: Long) {
        firstFork.activityDownloadProgress(bytes, totalBytes, durationNs)
        secondFork.activityDownloadProgress(bytes, totalBytes, durationNs)
    }

    override fun endActivity() {
        firstFork.endActivity()
        secondFork.endActivity()
    }

    override fun beginParallelActivity(activity: String): ActivityListener? {
        return splitBeginParallelActivity(activity, firstFork, secondFork)
    }
}