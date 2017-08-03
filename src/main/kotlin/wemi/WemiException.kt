package wemi

/**
 * Exception thrown when Wemi does not like how its APIs are used.
 */
open class WemiException : RuntimeException {

    val showStacktrace:Boolean

    constructor(message: String, showStacktrace:Boolean = true):super(message) {
        this.showStacktrace = showStacktrace
    }

    constructor(message: String, cause:Throwable, showStacktrace:Boolean = true):super(message, cause) {
        this.showStacktrace = showStacktrace
    }

    class KeyNotAssignedException(val key:Key<*>, val scope:Scope):WemiException("'${key.name}' not assigned in $scope", showStacktrace = false)
}