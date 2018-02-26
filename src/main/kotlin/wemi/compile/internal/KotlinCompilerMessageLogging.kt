package wemi.compile.internal

import org.slf4j.Logger
import org.slf4j.Marker
import wemi.boot.WemiRootFolder
import wemi.util.Color
import wemi.util.Format
import wemi.util.appendTimes
import wemi.util.format
import java.nio.file.Paths

/**
 * Mirror of [org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation]
 */
class KotlinCompilerMessageLocation (val path: String, val line: Int, val column: Int, val lineContent: String?)

private val LINE_SEPARATOR = System.lineSeparator()

/**
 * Log given message
 *
 * @param severity [org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity].name()
 */
fun Logger.render(marker: Marker?,
                  severity: String,
                  message: String,
                  location: KotlinCompilerMessageLocation?) {
    var important = false
    var color:Color? = null

    // Check if even enabled
    val enabled = when (severity) {
        "EXCEPTION" -> isErrorEnabled(marker)
        "ERROR" -> {
            important = true
            color = Color.Red
            isErrorEnabled(marker)
        }
        "STRONG_WARNING", "WARNING" -> {
            important = true
            color = Color.Yellow
            isWarnEnabled(marker)
        }
        "INFO" -> {
            color = Color.Blue
            isInfoEnabled(marker)
        }
        "LOGGING", "OUTPUT" -> isDebugEnabled(marker)
        else -> true
    }
    if (!enabled) {
        return
    }

    val result = StringBuilder()

    if (location != null) {
        val locationPath = Paths.get(location.path).toAbsolutePath()
        result.format(Color.White)
        if (locationPath.startsWith(WemiRootFolder)) {
            val relative = WemiRootFolder.relativize(locationPath)
            result.append(relative.toString())
        } else {
            result.append(locationPath.toString())
        }
        result.format().append(':')
        if (location.line > 0) {
            result.append(location.line).append(':')
            if (location.column > 0) {
                result.format(Color.White).append(location.column).format().append(':')
            }
        }
        result.append(' ')
    }

    result.format(foreground = color, format = if (important) Format.Bold else null)

    val firstNewline = message.indexOf(LINE_SEPARATOR) // They seem to use variable line separators

    // Start message on new line, if it would be longer than this and most of it would be path
    // Makes it more readable when errors are in files with long paths
    val WRAP_LINE_LENGTH = 150

    if (result.length > WRAP_LINE_LENGTH / 3
            && result.length + (if (firstNewline < 0) message.length else firstNewline) > WRAP_LINE_LENGTH) {
        result.append('\n')
    }

    if (firstNewline < 0) {
        result.append(message).format()
    } else {
        result.append(message, 0, firstNewline).format().append(message, firstNewline, message.length)
    }

    val lineContent = location?.lineContent
    if (lineContent != null) {
        result.append('\n').append(lineContent)
        if (location.column in 1..lineContent.length + 1) {
            result.append('\n').appendTimes(' ', location.column - 1).append('^')
        }
    }

    when (severity) {
        "EXCEPTION", "ERROR" -> {
            error(marker, "{}", result)
        }
        "STRONG_WARNING", "WARNING" -> {
            warn(marker, "{}", result)
        }
        "INFO" -> {
            info(marker, "{}", result)
        }
        "LOGGING", "OUTPUT" -> {
            debug(marker, "{}", result)
        }
        else -> {
            error(marker, "[{}]: {}", severity, result)
        }
    }
}

