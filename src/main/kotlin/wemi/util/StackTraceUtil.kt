package wemi.util

/**
 * Append the given throwable to the [StringBuilder].
 *
 * The throwable is printed with the same formatting as [Throwable.printStackTrace] has,
 * but the printed stack traces can be modified by the [stackMapper].
 */
inline fun StringBuilder.appendWithStackTrace(throwable: Throwable, crossinline stackMapper: ((Array<StackTraceElement>) -> List<StackTraceElement>) = { it.toList() }) {
    StackTraceUtil.appendWithStackTrace(this, throwable) {
        stackMapper(it)
    }
}