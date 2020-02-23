package wemi.boot

import com.esotericsoftware.jsonbeans.JsonWriter
import com.esotericsoftware.jsonbeans.OutputType
import org.slf4j.LoggerFactory
import wemi.AllConfigurations
import wemi.AllKeys
import wemi.AllProjects
import wemi.WemiException
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
    /** Standard Json output. Can easily output most of the object types. */
    JSON,
    /** Custom formatting for easier shell integration. May not correctly handle all value types. */
    SHELL
}

/**
 * Evaluates given task, possibly as a "machine-readable" command,
 * and prints the resulting JSON into [out].
 *
 * May exit process if the task evaluation fails.
 */
fun machineReadableEvaluateAndPrint(out: PrintStream, task: Task, format:MachineReadableOutputFormat) {
    LOG.info("> {}", task)
    if (task.isMachineReadableCommand) {
        when (task.key) {
            "version" -> {
                machineReadablePrint(out, WemiVersion, format)
                return
            }
            "projects" -> {
                machineReadablePrint(out, AllProjects.values, format)
                return
            }
            "configurations" -> {
                machineReadablePrint(out, AllConfigurations.values, format)
                return
            }
            "keys" -> {
                machineReadablePrint(out, AllKeys.values, format)
                return
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
                return
            }
            "defaultProject" -> {
                machineReadablePrint(out, findDefaultProject(Paths.get("."))?.name, format)
                return
            }
        }

        if (task.isMachineReadableOptional) {
            machineReadablePrint(out, null, format)
            return
        }

        LOG.error("Can't evaluate {} - unknown command", task)
        exitProcess(EXIT_CODE_MACHINE_OUTPUT_INVALID_COMMAND)
    }

    try {
        val (_, data, status) = task.evaluateKey(null, null)
        when (status) {
            TaskEvaluationStatus.Success -> {
                machineReadablePrint(out, data, format)
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
}

private fun Writer.shellPrint(thing:Any?, printStack:ArrayList<Any>) {
    if (thing == null) {
        write("\n")
    } else if (thing.javaClass.isArray) {
        if (printStack.contains(thing)) {
            write("<self-reference>\n")
        } else {
            printStack.add(thing)
            for (i in 0 until Array.getLength(thing)) {
                shellPrint(Array.get(thing, i), printStack)
                write("\n")
            }
            printStack.removeAt(printStack.lastIndex)
        }
    } else if (thing is CharSequence || thing is Number) {
        write(thing.toString())
        write("\n")
    } else if (thing is Path) {
        write(thing.toAbsolutePath().toString())
        write("\n")
    } else if (thing is File) {
        write(thing.absolutePath)
        write("\n")
    } else if (thing is Enum<*>) {
        write(thing.name)
        write("\n")
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
        write("\n")
    }
}

private fun machineReadablePrint(out: PrintStream, thing: Any?, format: MachineReadableOutputFormat) {
    val writer = OutputStreamWriter(out)
    when (format) {
        MachineReadableOutputFormat.JSON -> {
            val jsonWriter = JsonWriter(writer)
            jsonWriter.setOutputType(OutputType.json)
            jsonWriter.setQuoteLongValues(false)
            jsonWriter.writeValue(thing, null)
            writer.append(0.toChar())
        }
        MachineReadableOutputFormat.SHELL -> {
            writer.shellPrint(thing, ArrayList())
        }
    }
    writer.flush()
}

