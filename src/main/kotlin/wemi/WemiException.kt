package wemi

/**
 * Exception thrown when Wemi does not like how its APIs are used.
 */
class WemiException : RuntimeException {
    constructor(message: String):super(message)
    constructor(message: String, cause:Throwable):super(message, cause)
}