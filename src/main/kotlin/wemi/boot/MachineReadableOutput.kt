package wemi.boot

import com.darkyen.tproll.util.StringBuilderWriter
import com.esotericsoftware.jsonbeans.JsonWriter
import com.esotericsoftware.jsonbeans.OutputType
import org.slf4j.LoggerFactory
import wemi.AllConfigurations
import wemi.AllKeys
import wemi.AllProjects
import wemi.WemiException
import wemi.WithExitCode
import wemi.evaluateKeyOrCommand
import wemi.util.JsonWritable
import wemi.util.field
import wemi.util.writeArray
import wemi.util.writeObject
import wemi.util.writeValue
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.io.Writer
import java.lang.reflect.Array
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("MachineReadableOutput")

enum class MachineReadableOutputFormat {
    /** Standard Json output. Can easily output most of the object types.
     * Json value from each invocation is ended with a single NUL character. */
    JSON,
    /** Custom formatting for easier shell integration. May not correctly handle all value types.
     * Printed values should end with newlines. */
    SHELL
}

/** Given some value, print it out using the specified format into the given output.
 * Return true if the format is supported and value was printed or false otherwise,
 * in which case default formatter will take over. */
typealias MachineReadableFormatter<T> = (format:MachineReadableOutputFormat, value:T, out:Writer) -> Boolean

fun newMachineReadableJsonPrinter(out:Writer): JsonWriter {
    val jsonWriter = JsonWriter(out)
    jsonWriter.setOutputType(OutputType.json)
    jsonWriter.setQuoteLongValues(false)
    return jsonWriter
}

/**
 * Evaluates given task, possibly as a "machine-readable" command,
 * and prints the resulting JSON into [out].
 *
 * May exit process if the task evaluation fails.
 */
internal fun machineReadableEvaluateAndPrint(out: PrintStream, task: Task, format:MachineReadableOutputFormat): WithExitCode? {
    LOG.info("> {}", task)
    if (task.isMachineReadableCommand) {
        when (task.key) {
            "version" -> {
                machineReadablePrint(out, WemiVersion, format)
                return null
            }
            "projects" -> {
                machineReadablePrint(out, AllProjects.values, format)
                return null
            }
            "configurations" -> {
                machineReadablePrint(out, AllConfigurations.values, format)
                return null
            }
            "keys" -> {
                machineReadablePrint(out, AllKeys.values, format)
                return null
            }
            "keysWithDescription" -> {
                machineReadablePrint(out, object : JsonWritable {
                    override fun JsonWriter.write() {
                        writeArray {
                            for (key in AllKeys.values) {
                                writeObject {
                                    field("name", key.name)
                                    field("description", key.description)
                                }
                            }
                        }
                    }
                }, format)
                return null
            }
            "defaultProject" -> {
                machineReadablePrint(out, findDefaultProject(Paths.get("."))?.name, format)
                return null
            }
        }

        if (task.isMachineReadableOptional) {
            machineReadablePrint(out, null, format)
            return null
        }

        LOG.error("Can't evaluate {} - unknown command", task)
        exitProcess(EXIT_CODE_MACHINE_OUTPUT_INVALID_COMMAND)
    }

    try {
        val (key, data, status) = evaluateKeyOrCommand(task, null, null)
        when (status) {
            TaskEvaluationStatus.Success -> {
                @Suppress("UNCHECKED_CAST")
                machineReadablePrint(out, data, format, key?.machineReadableFormatter as? MachineReadableFormatter<Any?>)
                return data as? WithExitCode
            }
            TaskEvaluationStatus.NoProject -> {
                val projectString = data as String?
                if (projectString != null) {
                    LOG.error("Can't evaluate {} - no project named {} found", task, projectString)
                } else {
                    LOG.error("Can't evaluate {} - no project specified", task)
                }
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_NO_PROJECT_ERROR)
            }
            TaskEvaluationStatus.NoConfiguration -> {
                val configString = data as String
                LOG.error("Can't evaluate {} - no configuration named {} found", task, configString)
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_NO_CONFIGURATION_ERROR)
            }
            TaskEvaluationStatus.NoKey -> {
                val keyString = data as String
                LOG.error("Can't evaluate {} - no key named {} found", task, keyString)
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_NO_KEY_ERROR)
            }
            TaskEvaluationStatus.NotAssigned -> {
                if (task.isMachineReadableOptional) {
                    machineReadablePrint(out, null, format)
                } else {
                    val error = data as WemiException.KeyNotAssignedException
                    LOG.error("Can't evaluate {} - {}{} not set", task, error.scope.toString(), error.key.name)
                    exitProcess(EXIT_CODE_MACHINE_OUTPUT_KEY_NOT_SET_ERROR)
                }
            }
            TaskEvaluationStatus.Exception -> {
                val we = data as WemiException

                val message = we.message
                if (we.showStacktrace || message == null || message.isBlank()) {
                    LOG.error("Can't evaluate {} - exception", task, we)
                } else {
                    LOG.error("Can't evaluate {} - {}", task, we.message)
                    LOG.debug("Can't evaluate {} - exception to previous message", task, we)
                }
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR)
            }
            TaskEvaluationStatus.Command -> throw IllegalArgumentException()
        }
    } catch (e: Throwable) {
        LOG.error("Can't evaluate {} - fatal exception", task, e)
        exitProcess(EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR)
    }
    return null
}

internal fun Writer.writeShellEscaped(value:String, oneLine:Boolean) {
    val safeWithoutQuotes = value.all { it !in "|&;<>()\$`\\\"' \t\n*?[#˜=%" }
    if (safeWithoutQuotes) {
        write(value)
    } else {
        append('\'')
        for (c in value) {
            if ((c < ' ' && c != '\t' && c != '\u000B'/*VT*/) || c == '\u007F') {
                if (c != '\n' || oneLine) {
                    throw WemiException("Value contains dangerous character: \\u%04x".format(c.toInt()))
                }
            }
            if (c == '\'') {
                write("'\"'\"'")
            } else {
                append(c)
            }
        }
        append('\'')
    }
}

private fun Writer.shellPrint(thing:Any?, printStack:ArrayList<Any>) {
    if (thing == null) {
        append('\n')
    } else if (thing.javaClass.isArray) {
        if (printStack.contains(thing)) {
            write("<self-reference>\n")
        } else {
            printStack.add(thing)
            for (i in 0 until Array.getLength(thing)) {
                shellPrint(Array.get(thing, i), printStack)
                append('\n')
            }
            printStack.removeAt(printStack.lastIndex)
        }
    } else if (thing is CharSequence || thing is Number) {
        append(thing.toString()).append('\n')
    } else if (thing is Path) {
        append(thing.toAbsolutePath().toString()).append('\n')
    } else if (thing is File) {
        append(thing.absolutePath).append('\n')
    } else if (thing is Enum<*>) {
        append(thing.name).append('\n')
    } else if (thing is Map<*, *>) {
        if (printStack.contains(thing)) {
            write("<self-reference>\n")
        } else {
            printStack.add(thing)
            for (entry in thing.entries) {
                shellPrint(entry.key, printStack)
                shellPrint(entry.value, printStack)
            }
            printStack.removeAt(printStack.lastIndex)
        }
    } else if (thing is Iterable<*>) {
        if (printStack.contains(thing)) {
            write("<self-reference>\n")
        } else {
            printStack.add(thing)
            for (element in thing) {
                shellPrint(element, printStack)
            }
            printStack.removeAt(printStack.lastIndex)
        }
    } else {
        val jsonWriter = JsonWriter(this)
        jsonWriter.setOutputType(OutputType.minimal)
        jsonWriter.setQuoteLongValues(false)
        jsonWriter.writeValue(thing, null)
        append('\n')
    }
}

private fun <V>machineReadablePrint(out: PrintStream, thing: V, format: MachineReadableOutputFormat, formatter: MachineReadableFormatter<V>? = null) {
    if (formatter != null) {
        val sb = StringBuilder()
        val sbw = StringBuilderWriter(sb)
        try {
            if (formatter(format, thing, sbw)) {
                out.append(sb)
                if (format == MachineReadableOutputFormat.JSON) {
                    out.append(0.toChar())
                }
                return
            }
        } catch (e:Exception) {
            LOG.warn("Failed to print value using {}", formatter, e)
        }
    }

    val writer = OutputStreamWriter(out)
    when (format) {
        MachineReadableOutputFormat.JSON -> {
            val jsonWriter = newMachineReadableJsonPrinter(writer)
            jsonWriter.writeValue(thing, null)
            writer.append(0.toChar())
        }
        MachineReadableOutputFormat.SHELL -> {
            writer.shellPrint(thing, ArrayList())
        }
    }
    writer.flush()
}

