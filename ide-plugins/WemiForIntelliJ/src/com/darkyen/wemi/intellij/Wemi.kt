package com.darkyen.wemi.intellij

import com.darkyen.wemi.intellij.util.Version
import com.darkyen.wemi.intellij.util.readFully
import com.darkyen.wemi.intellij.util.toPath
import com.esotericsoftware.jsonbeans.JsonReader
import com.esotericsoftware.jsonbeans.JsonValue
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.registry.Registry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

// Must be a subset of Kotlin file extensions
val WemiBuildFileExtensions = listOf("kt")

val WemiProjectSystemId = ProjectSystemId("WEMI", "Wemi").apply {
    // Do not launch our WemiProjectResolver and WemiTaskManager in external process,
    // because it just adds delays and is messy
    Registry.get("${id}${ExternalSystemConstants.USE_IN_PROCESS_COMMUNICATION_REGISTRY_KEY_SUFFIX}").setValue(true)
}

const val WemiBuildScriptProjectName = "wemi-build"

const val WemiLauncherFileName = "wemi"

const val WemiBuildDirectoryName = "build"

/** Finds project's Wemi launcher, if present.
 * Should be fairly cheap. */
fun findWemiLauncher(project:Project):WemiLauncher? {
    if (project.isDefault)
        return null
    val wemiJar = project.guessProjectDir().toPath()?.resolve(WemiLauncherFileName)?.toAbsolutePath() ?: return null

    if (!Files.isRegularFile(wemiJar)) return null

    return WemiLauncher(wemiJar)
}

fun findWemiLauncher(projectDir:String):WemiLauncher? {
    val wemiJar = Paths.get(projectDir).resolve(WemiLauncherFileName).toAbsolutePath() ?: return null

    if (!Files.isRegularFile(wemiJar)) return null

    return WemiLauncher(wemiJar)
}

class WemiLauncher internal constructor(val file: Path) {

    /** Wemi launchers pre-0.10 were self contained Jars and were launched differently. */
    private val versionPre010:Boolean by lazy {
        val result = try {
            ZipFile(file.toFile()).close()
            true
        } catch (t:Throwable) {
            false
        }
        result
    }

    fun createMachineReadableResolverSession(javaExecutable: String, jvmOptions: List<String>, env: Map<String, String>, inheritEnv: Boolean, prefixConfigurations: Array<String>, allowBrokenBuildScripts:Boolean, tracker:ExternalStatusTracker?):WemiLauncherSession {
        if (versionPre010) {
            return pre010_createMachineReadableResolverSession(if(javaExecutable.isBlank()) "java" else javaExecutable, jvmOptions, env, inheritEnv, prefixConfigurations, allowBrokenBuildScripts, tracker)
        }
        val command = GeneralCommandLine()
        command.exePath = file.toAbsolutePath().toString()
        command.charset = Charsets.UTF_8
        command.environment.putAll(env)
        command.environment["WEMI_COLOR"] = "false"
        command.environment["WEMI_UNICODE"] = "true"
        command.environment["WEMI_JAVA_OPTS"] = jvmOptions.joinToString(" ")
        if (javaExecutable.isNotBlank()) {
            command.environment["WEMI_JAVA"] = javaExecutable
        }
        command.workDirectory = file.parent.toFile()
        command.withParentEnvironmentType(if (inheritEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)
        command.isRedirectErrorStream = false

        command.addParameter("--interactive")
        command.addParameter("--machine-readable-output")
        if (allowBrokenBuildScripts) {
            command.addParameter("--allow-broken-build-scripts")
        }

        return WemiLauncherSession(command, prefixConfigurations, tracker)
    }

    fun createTaskSession(javaExecutable: String, jvmOptions: List<String>, env: Map<String, String>, inheritEnv: Boolean, tasks: List<String>, tracker:ExternalStatusTracker?):WemiLauncherSession {
        if (versionPre010) {
            return pre010_createTaskSession(if(javaExecutable.isBlank()) "java" else javaExecutable, jvmOptions, env, inheritEnv, tasks, tracker)
        }
        val command = GeneralCommandLine()
        command.exePath = file.toAbsolutePath().toString()
        command.charset = Charsets.UTF_8
        command.environment.putAll(env)
        command.environment["WEMI_COLOR"] = "true"
        command.environment["WEMI_UNICODE"] = "true"
        command.environment["WEMI_JAVA_OPTS"] = jvmOptions.joinToString(" ")
        if (javaExecutable.isNotBlank()) {
            command.environment["WEMI_JAVA"] = javaExecutable
        }
        command.workDirectory = file.parent.toFile()
        command.withParentEnvironmentType(if (inheritEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)
        command.isRedirectErrorStream = false

        command.addParameters(tasks)

        return WemiLauncherSession(command, tracker = tracker)
    }

    private fun pre010_createMachineReadableResolverSession(javaExecutable: String, jvmOptions: List<String>, env: Map<String, String>, inheritEnv: Boolean, prefixConfigurations: Array<String>, allowBrokenBuildScripts:Boolean, tracker:ExternalStatusTracker?):WemiLauncherSession {
        val command = GeneralCommandLine()
        command.exePath = javaExecutable
        command.charset = Charsets.UTF_8
        command.environment.putAll(env)
        command.environment["WEMI_COLOR"] = "false"
        command.environment["WEMI_UNICODE"] = "true"
        command.workDirectory = file.parent.toFile()
        command.withParentEnvironmentType(if (inheritEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)
        jvmOptions.forEach {
            command.addParameter(it)
        }
        command.addParameter("-jar")
        command.addParameter(file.toString())
        command.isRedirectErrorStream = false

        command.addParameter("--interactive")
        command.addParameter("--machine-readable-output")
        if (allowBrokenBuildScripts) {
            command.addParameter("--allow-broken-build-scripts")
        }

        return WemiLauncherSession(command, prefixConfigurations, tracker)
    }

    private fun pre010_createTaskSession(javaExecutable: String, jvmOptions: List<String>, env: Map<String, String>, inheritEnv: Boolean, tasks: List<String>, tracker:ExternalStatusTracker?):WemiLauncherSession {
        val command = GeneralCommandLine()
        command.exePath = javaExecutable
        command.charset = Charsets.UTF_8
        command.environment.putAll(env)
        command.environment["WEMI_COLOR"] = "true"
        command.environment["WEMI_UNICODE"] = "true"
        command.workDirectory = file.parent.toFile()
        command.withParentEnvironmentType(if (inheritEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)
        command.addParameters(jvmOptions)
        command.addParameter("-jar")
        command.addParameter(file.toString())
        command.isRedirectErrorStream = false

        command.addParameters(tasks)

        return WemiLauncherSession(command, tracker = tracker)
    }
}

class ExternalStatusTracker(val id:ExternalSystemTaskId, private val listener:ExternalSystemTaskNotificationListener) {

    private fun updateListenerStatus() {
        val message = task?.let { "$stage - $it" } ?: stage
        listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, message))
    }

    var stage:String = "Initialized"
        set(value) {
            if (field != value) {
                field = value
                updateListenerStatus()
            }
        }

    var task:CharSequence? = null
        set(value) {
            if (field != value) {
                field = value
                updateListenerStatus()
            }
        }

}

class WemiLauncherSession(
        private val commandLine: GeneralCommandLine,
        private val prefixConfigurations: Array<String> = emptyArray(),
        val tracker: ExternalStatusTracker?) {

    var wemiVersion: Version = Version.NONE
    private var process = SessionProcess(commandLine)

    fun task(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):Result {
        val taskPath = StringBuilder()
        if (project != null) {
            taskPath.append(project).append('/')
        }
        if (includeUserConfigurations) {
            for (configuration in prefixConfigurations) {
                taskPath.append(configuration).append(':')
            }
        }
        for (configuration in configurations) {
            taskPath.append(configuration).append(':')
        }
        taskPath.append(task)

        try {
            if (process.isDead()) {
                tracker?.task = "Restarting..."
                process.close()
                process = SessionProcess(commandLine)
            }

            tracker?.task = taskPath
            val taskData = ByteArrayOutputStream()
            val taskOutput = ByteArrayOutputStream()
            val taskResult = process.task(taskPath, taskData, taskOutput)

            val jsonReader = JsonReader()
            val jsonString = taskData.contentToString()
            val json = jsonReader.parse(jsonString)

            return Result(statusForNumber(taskResult), json, taskOutput.contentToString())
        } finally {
            tracker?.task = null
        }
    }

    fun readOutputInteractive(stdout:OutputStream, stderr:OutputStream, input: InputStream) {
        process.readOutputInteractive(stdout, stderr, input)
    }

    fun done() {
        process.close()
    }

    private class SessionProcess(commandLine: GeneralCommandLine) {
        private val process = commandLine.createProcess()
        private val outputStream = process.outputStream
        private val output = OutputStreamWriter(outputStream, Charsets.UTF_8)
        private val inputStream = process.inputStream
        private val errorStream = process.errorStream

        private val buffer = ByteArray(512)

        private val extraDataStreamCache = ByteArrayOutputStream()
            get() {
                field.reset()
                return field
            }

        fun emptyBuffers(occasion:String) {
            try {
                extraDataStreamCache.apply {
                    readFully(this, inputStream, buffer)
                    if (this.size() > 0) {
                        LOG.warn("Extra data found $occasion: ${contentToString()}")
                    }
                }
            } catch (e:Exception) {
                LOG.warn("Exception while checking for extra data in stdout", e)
            }

            try {
                extraDataStreamCache.apply {
                    readFully(this, errorStream)
                    if (this.size() > 0) {
                        LOG.warn("Extra output found $occasion: ${contentToString()}")
                    }
                }
            } catch (e:Exception) {
                LOG.warn("Exception while checking for extra data in stderr", e)
            }
        }

        fun close():Int {
            emptyBuffers("when closing the session")
            noThrow("close inputStream") { inputStream.close() }
            noThrow("close errorStream") { errorStream.close() }
            noThrow("close output") { output.close() }


            if (!process.waitForSafe(10, TimeUnit.SECONDS)) {
                LOG.warn("Session $this does not want to end, shutting down")
                process.destroy()
                if (!process.waitForSafe(10, TimeUnit.SECONDS)) {
                    LOG.warn("Session $this is probably frozen, forcibly shutting down")
                    process.destroyForcibly()
                    if (!process.waitForSafe(10, TimeUnit.SECONDS)) {
                        LOG.warn("Session $this is frozen and can't be shut down")
                        return -1
                    }
                }
            }
            return process.exitValue()
        }

        fun isDead():Boolean = !process.isAlive

        fun task(task: CharSequence, taskData: OutputStream, taskOutput: OutputStream):Int {
            try {
                // Read extra data/output in buffers
                emptyBuffers("before task '$task' was run")

                output.append(task).append('\n')
                output.flush()

                // Read data until \0
                val dataOk = run {
                    val extraData:ByteArrayOutputStream

                    while (true) {
                        // Read all output, process may be blocking on it
                        readFully(taskOutput, errorStream)

                        // Read data, if any
                        val available = minOf(inputStream.available(), buffer.size)
                        val size = inputStream.read(buffer, 0, available)
                        if (size == -1) {
                            // Premature exit, error perhaps?
                            return@run false
                        }

                        var zeroAt = -1
                        for (i in 0 until size) {
                            if (buffer[i] == 0.toByte()) {
                                zeroAt = i
                                break
                            }
                        }

                        if (zeroAt == -1) {
                            // There is still more data
                            taskData.write(buffer, 0, size)
                            if (available == 0) {
                                // Still no data... are we dead?
                                if (!process.isAlive) {
                                    return@run false
                                }
                                Thread.yield()
                            }
                        } else {
                            taskData.write(buffer, 0, zeroAt)
                            if (zeroAt == size - 1) {
                                // There is no more data, and we have found a correct end
                                return@run true
                            } else {
                                // We have all data we need, but there is still some more data?
                                extraData = extraDataStreamCache
                                extraData.write(buffer, zeroAt + 1, size - (zeroAt + 1))
                                break
                            }
                        }
                    }

                    readFully(extraData, inputStream)
                    LOG.warn("Extra data for task '$task' found: ${extraData.contentToString()}")
                    return@run true
                }

                // We have all the data we need, maybe even more.
                // taskOutput has been filled slowly during the data reading, read whatever it may contain next
                readFully(taskOutput, errorStream)

                return if (dataOk) {
                    ResultStatus.SUCCESS.number
                } else {
                    close()
                }
            } catch (e:Exception) {
                LOG.error("Error while evaluating task '$task'", e)
                throw e
            }
        }

        fun readOutputInteractive(stdout: OutputStream, stderr: OutputStream, input:InputStream) {
            while (true) {
                val out = readFully(stdout, inputStream)
                val err = readFully(stderr, errorStream)
                if (readFully(outputStream, input) > 0) {
                    outputStream.flush()
                }

                if (out == 0 && err == 0 && !process.isAlive) {
                    break
                }

                //TODO Solve this busy-loop
                Thread.sleep(1)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WemiLauncherSession::class.java)

        data class Result (val status:ResultStatus, val data:JsonValue?, val output:String)

        enum class ResultStatus(val number:Int) {
            SUCCESS(0),
            UNKNOWN_ERROR(1),
            ARGUMENT_ERROR(2),
            BUILD_SCRIPT_COMPILATION_ERROR(3),
            MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR(4),
            MACHINE_OUTPUT_NO_PROJECT_ERROR(5),
            MACHINE_OUTPUT_NO_CONFIGURATION_ERROR(6),
            MACHINE_OUTPUT_NO_KEY_ERROR(7),
            MACHINE_OUTPUT_KEY_NOT_SET_ERROR(8)
        }

        fun statusForNumber(number: Int):ResultStatus {
            return ResultStatus.values().find { it.number == number } ?: ResultStatus.UNKNOWN_ERROR
        }

        private fun ByteArrayOutputStream.contentToString():String {
            return String(toByteArray(), Charsets.UTF_8)
        }

        private inline fun noThrow(label:String, operation:()->Unit) {
            try {
                operation()
            } catch (e:Exception) {
                LOG.debug("noThrow($label)", e)
            }
        }

        private fun Process.waitForSafe(timeout:Long, unit:TimeUnit):Boolean {
            return try {
                val interrupted = Thread.interrupted()
                val result = this.waitFor(timeout, unit)
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
                result
            } catch (e:InterruptedException) {
                LOG.debug("waitForSafe interrupt ignored")
                !this.isAlive
            }
        }
    }
}
