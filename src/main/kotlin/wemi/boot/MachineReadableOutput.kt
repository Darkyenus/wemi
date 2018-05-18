package wemi.boot

import com.esotericsoftware.jsonbeans.JsonWriter
import com.esotericsoftware.jsonbeans.OutputType
import org.slf4j.LoggerFactory
import wemi.*
import wemi.util.*
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.nio.file.Paths
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("MachineReadableOutput")

/**
 * Evaluates given task, possibly as a "machine-readable" command,
 * and prints the resulting JSON into [out].
 *
 * May exit process if the task evaluation fails.
 */
fun machineReadableEvaluateAndPrint(out: PrintStream, task: Task) {
    LOG.info("> {}", task)
    if (task.isMachineReadableCommand) {
        when (task.key) {
            "version" -> {
                machineReadablePrint(out, WemiVersion)
                return
            }
            "projects" -> {
                machineReadablePrint(out, AllProjects.values)
                return
            }
            "configurations" -> {
                machineReadablePrint(out, AllConfigurations.values)
                return
            }
            "keys" -> {
                machineReadablePrint(out, AllKeys.values)
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
                })
                return
            }
            "buildScript" -> {
                machineReadablePrint(out, object : JsonWritable {

                    override fun JsonWriter.write() {
                        val buildFile = WemiBuildScript
                        if (buildFile == null) {
                            writeValue(null, null)
                        } else {
                            writeObject {
                                field("buildFolder", WemiBuildFolder)
                                field("cacheFolder", WemiCacheFolder)
                                fieldCollection("sources", buildFile.sources)
                                field("scriptJar", buildFile.scriptJar)
                                fieldCollection("classpath", buildFile.externalClasspath)
                            }
                        }
                    }
                })
                return
            }
            "defaultProject" -> {
                machineReadablePrint(out, CLI.findDefaultProject(Paths.get("."))?.name)
                return
            }
        }

        if (task.isMachineReadableOptional) {
            machineReadablePrint(out, null)
            return
        }

        LOG.error("Can't evaluate {} - unknown command", task)
        exitProcess(EXIT_CODE_MACHINE_OUTPUT_INVALID_COMMAND)
    }

    try {
        val (_, data, status) = task.evaluateKey(null)
        when (status) {
            TaskEvaluationStatus.Success -> {
                machineReadablePrint(out, data)
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
                    machineReadablePrint(out, null)
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

private fun machineReadablePrint(out: PrintStream, thing: Any?) {
    val writer = OutputStreamWriter(out)
    val jsonWriter = JsonWriter(writer)
    jsonWriter.setOutputType(OutputType.json)
    jsonWriter.setQuoteLongValues(false)
    jsonWriter.writeValue(thing, null)
    writer.append(0.toChar())
    writer.flush()
}

