package wemi.util

import java.util.*


/**
 * Append the given throwable to the [StringBuilder].
 *
 * The throwable is printed with the same formatting as [Throwable.printStackTrace] has,
 * but the printed stack traces can be modified by the [stackMapper].
 */
fun StringBuilder.appendWithStackTrace(throwable: Throwable, stackMapper: ((Array<StackTraceElement>) -> List<StackTraceElement>) = { it.toList() }) {
    val alreadyPrinted = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    alreadyPrinted.add(throwable)

    val trace = stackMapper(throwable.stackTrace)
    val cause = throwable.cause
    val suppressed = throwable.suppressed

    // Print our stack trace
    append(throwable).append('\n')
    for (traceElement in trace) {
        append("\tat ").append(traceElement).append('\n')
    }

    // Print suppressed exceptions, if any
    for (se in suppressed) {
        appendAdditionalStackTrace(se, this, trace, "Suppressed: ", "\t", alreadyPrinted, stackMapper)
    }

    // Print cause, if any
    if (cause != null) {
        appendAdditionalStackTrace(cause, this, trace, "Caused by: ", "", alreadyPrinted, stackMapper)
    }
}

/**
 * Print our stack trace as an enclosed exception for the specified
 * stack trace.
 */
private fun appendAdditionalStackTrace(throwable: Throwable,
                                       sb: StringBuilder,
                                       enclosingTrace: List<StackTraceElement>,
                                       caption: String,
                                       prefix: String,
                                       alreadyPrinted: MutableSet<Throwable>,
                                       stackMapper: ((Array<StackTraceElement>) -> List<StackTraceElement>)) {

    if (alreadyPrinted.contains(throwable)) {
        sb.append("\t[CIRCULAR REFERENCE:").append(throwable).append("]\n")
    } else {
        alreadyPrinted.add(throwable)

        val trace = stackMapper(throwable.stackTrace)
        val cause = throwable.cause
        val suppressed = throwable.suppressed

        // Compute number of frames in common between this and enclosing trace
        var m = trace.size - 1
        var n = enclosingTrace.size - 1
        while (m >= 0 && n >= 0 && trace[m] == enclosingTrace[n]) {
            m--
            n--
        }
        val framesInCommon = trace.size - 1 - m

        // Print our stack trace
        sb.append(prefix + caption + throwable).append('\n')
        for (i in 0..m) {
            sb.append(prefix).append("\tat ").append(trace[i]).append('\n')
        }

        if (framesInCommon != 0) {
            sb.append(prefix).append("\t... ").append(framesInCommon).append(" more").append('\n')
        }

        // Print suppressed exceptions, if any
        for (se in suppressed)
            appendAdditionalStackTrace(se, sb, trace, "Suppressed: ",
                    prefix + "\t", alreadyPrinted, stackMapper)

        // Print cause, if any
        if (cause != null) {
            appendAdditionalStackTrace(cause, sb, trace, "Caused by: ", prefix, alreadyPrinted, stackMapper)
        }
    }
}