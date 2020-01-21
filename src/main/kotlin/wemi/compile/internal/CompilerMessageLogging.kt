package wemi.compile.internal

import org.slf4j.Logger
import org.slf4j.Marker
import wemi.boot.WemiRootFolder
import wemi.util.Color
import wemi.util.Format
import wemi.util.absolutePath
import wemi.util.appendPathTagBegin
import wemi.util.appendPathTagEnd
import wemi.util.format
import java.io.BufferedReader
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject

/**
 * Mirror of [org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation]
 */
class MessageLocation(val path: String, val line: Int, val column: Int, val lineContent: String?, val tabColumnCompensation:Int = 1)

private val LINE_SEPARATOR = System.lineSeparator()

/**
 * Log given message
 *
 * @param severity [org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity].name()
 *                  or [javax.tools.Diagnostic.Kind].name()
 */
fun Logger.render(marker: Marker?,
                  severity: String,
                  message: String,
                  location: MessageLocation?) {
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
        "STRONG_WARNING", "WARNING", "MANDATORY_WARNING" -> {
            important = true
            color = Color.Yellow
            isWarnEnabled(marker)
        }
        "INFO", "NOTE" -> {
            color = Color.Blue
            isInfoEnabled(marker)
        }
        "LOGGING", "OUTPUT", "OTHER" -> isDebugEnabled(marker)
        else -> true
    }
    if (!enabled) {
        return
    }

    val result = StringBuilder()

    if (location != null) {
        val locationPath = Paths.get(location.path).toAbsolutePath()
        result.format(Color.Black)
        result.appendPathTagBegin()
        if (locationPath.startsWith(WemiRootFolder)) {
            val relative = WemiRootFolder.relativize(locationPath)
            result.append(relative.toString())
        } else {
            result.append(locationPath.toString())
        }
        if (location.line > 0) {
            result.appendPathTagEnd(':')
            result.append(location.line)
            if (location.column > 0) {
                result.format(Color.White).append(':').append(location.column).format()
            }
            result.append(' ')
        } else {
            result.appendPathTagEnd(' ')
        }
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
        var remainingSpaces = location.column - 1
        if (remainingSpaces >= 0) {
            result.append('\n')
            var i = 0
            while (remainingSpaces > 0) {
                if (lineContent[i] == '\t') {
                    result.append('\t')
                    remainingSpaces -= location.tabColumnCompensation
                } else {
                    result.append(' ')
                    remainingSpaces -= 1
                }
                i++
            }

            result.append('^')
        }
    }

    when (severity) {
        "EXCEPTION", "ERROR" -> {
            error(marker, "{}", result)
        }
        "STRONG_WARNING", "WARNING", "MANDATORY_WARNING" -> {
            warn(marker, "{}", result)
        }
        "INFO", "NOTE" -> {
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

private val JavaLintExtractorPattern = Pattern.compile(": \\[([a-zA-Z-._0-9]+)\\]")

fun createJavaObjectFileDiagnosticLogger(log:Logger):DiagnosticListener<JavaFileObject> {
    return DiagnosticListener { diagnostic ->
        val source = diagnostic.source
        val location: MessageLocation? =
                if (source == null) {
                    null
                } else {
                    val lineNumber = diagnostic.lineNumber
                    var lineContent:String? = null
                    BufferedReader(diagnostic.source.openReader(true)).use {
                        var line = 0L
                        while (true) {
                            val l = it.readLine() ?: break
                            line++
                            if (line == lineNumber) {
                                lineContent = l
                                break
                            }
                        }
                    }

                    MessageLocation(
                            Paths.get(source.toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS).absolutePath,
                            lineNumber.toInt(),
                            diagnostic.columnNumber.toInt(),
                            lineContent,
                            tabColumnCompensation = 8)
                }

        var lint:String? = null
        // Try to extract lint info from the message (HACK)
        try {
            // If running on Java <= 1.8, use reflection.
            // Otherwise ("java.version" will start with 9., 10., etc.) or if this fails,
            // use different heuristics, because using reflection will cause warnings about illegal reflective access.
            // (TODO: reconsider how to deal with this after Wemi is modularised)
            if (System.getProperty("java.version")?.startsWith("1.") == true) {
                // It is sadly not available through public api.
                var jc = diagnostic
                if (jc.javaClass.name == "com.sun.tools.javac.api.ClientCodeWrapper\$DiagnosticSourceUnwrapper") {
                    @Suppress("UNCHECKED_CAST")
                    jc = jc.javaClass.getDeclaredField("d").get(jc) as Diagnostic<JavaFileObject>
                }
                if (jc.javaClass.name == "com.sun.tools.javac.util.JCDiagnostic") {
                    val lintCategory = jc.javaClass.getMethod("getLintCategory").invoke(jc)
                    if (lintCategory != null) {
                        lint = lintCategory.javaClass.getField("option").get(lintCategory) as String?
                    }
                } else {
                    log.debug("Failed to extract lint information from {}", diagnostic.javaClass)
                }
            }
        } catch (ex:Exception) {
            log.debug("Failed to extract lint information from {}", diagnostic, ex)
        }
        if (lint == null) {
            val match = JavaLintExtractorPattern.matcher(diagnostic.toString())
            if (match.find()) {
                lint = match.group(1)
            }
        }

        var message = diagnostic.getMessage(Locale.getDefault())
        if (lint != null) {
            // Mimic default format
            message = "[$lint] $message"
        }

        log.render(null, diagnostic.kind.name, message, location)
    }

}

