package wemi

/**
 * Exception thrown when Wemi does not like how its APIs are used.
 */
@Suppress("unused")
open class WemiException : RuntimeException {

    /**
     * If stacktrace of this exception should be shown.
     *
     * Set to false if the stacktrace would only confuse the user and is not important to the problem resolution.
     */
    val showStacktrace: Boolean

    constructor(message: String, showStacktrace: Boolean = true) : super(message) {
        this.showStacktrace = showStacktrace
    }

    constructor(message: String, cause: Throwable, showStacktrace: Boolean = true) : super(message, cause) {
        this.showStacktrace = showStacktrace
    }

    /**
     * Special version of the [WemiException], thrown when the [key] that is being evaluated is not set in [scope]
     * it is being evaluated in.
     *
     * @see EvalScope.get can throw this
     */
    class KeyNotAssignedException(val key: Key<*>, val scope: Scope) : WemiException("'${key.name}' not assigned in $scope", showStacktrace = false)

    /**
     * Special version of the [WemiException], thrown typically by implementations of [wemi.keys.compile] to indicate
     * compilation error, caused by invalid source code.
     */
    class CompilationException : WemiException {
        constructor(message:String) : super(message, showStacktrace = false)
        constructor(message: String, cause: Throwable) : super(message, cause, showStacktrace = false)
    }
}