@file:Suppress("BooleanLiteralArgument")

package com.darkyen.wemi.intellij

import com.darkyen.wemi.intellij.file.isWemiLauncher
import com.darkyen.wemi.intellij.file.isWemiScriptSource
import com.darkyen.wemi.intellij.options.WemiLauncherOptions
import com.darkyen.wemi.intellij.util.Failable
import com.darkyen.wemi.intellij.util.SessionState
import com.darkyen.wemi.intellij.util.Version
import com.darkyen.wemi.intellij.util.collectOutputLineAndKill
import com.esotericsoftware.jsonbeans.JsonException
import com.esotericsoftware.jsonbeans.JsonReader
import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.OutputType
import com.intellij.execution.process.ProcessIOExecutorService
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.StreamUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ConcurrencyUtil
import com.intellij.util.EnvironmentUtil
import com.pty4j.PtyProcessBuilder
import java.io.CharArrayWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.zip.ZipFile
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

// Must be a subset of Kotlin file extensions
val WemiBuildFileExtensions = listOf("kt")

const val WemiBuildScriptProjectName = "wemi-build"

const val WemiLauncherFileName = "wemi"

const val WemiBuildDirectoryName = "build"

/** Given any Wemi related file, return the root of the Wemi project. */
fun wemiDirectoryToImport(base: VirtualFile): VirtualFile? {
    if (base.isDirectory) {
        // This is a wemi folder with wemi launcher?
        val wemiLauncher = base.findChild(WemiLauncherFileName)
        if (wemiLauncher.isWemiLauncher()) {
            return base
        }
        // Ok, no launcher, but maybe build has some build scripts?
        val buildFolder = base.findChild(WemiBuildDirectoryName)
        if (buildFolder != null && buildFolder.children.any { it.isWemiScriptSource() }) {
            return base
        }

        // Maybe this is the build folder?
        if (base.name.equals(WemiBuildDirectoryName, ignoreCase = true) && base.children.any { it.isWemiScriptSource() }) {
            return base.parent
        }
    } else {
        // isFile
        // Maybe this is the wemi launcher?
        if (base.isWemiLauncher()) {
            return base.parent
        }
        // Maybe this is one of wemi sources?
        if (base.isWemiScriptSource()) {
            val buildFolder = base.parent
            if (buildFolder.name.equals(WemiBuildDirectoryName, ignoreCase = true)) {
                return buildFolder.parent
            }
        }
    }
    return null
}

class WemiLauncher internal constructor(val file: Path, private val windowsShellExecutable:String) {

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

    private fun wemiHome(options: WemiLauncherOptions):Path? {
        if (versionPre010) {
            return null
        }

        return try {
            val process = createWemiProcess(options, false, false, listOf("--print-wemi-home"), emptyList(), -1, DebugScheme.DISABLED, pty=false).first
            StreamUtil.closeStream(process.outputStream)
            val result = process.collectOutputLineAndKill(10, TimeUnit.SECONDS, true)
            if (result != null && result.isNotBlank()) {
                LOG.info("Found wemiHome of $file at $result")
                return Paths.get(result)
            } else {
                return null
            }
        } catch (e:Exception) {
            LOG.warn("Failed to resolve wemiHome", e)
            null
        }
    }

    enum class DebugScheme {
        DISABLED,
        WEMI_BUILD_SCRIPTS,
        WEMI_FORKED_PROCESSES
    }

    private fun pre010_createWemiProcess(
            options: WemiLauncherOptions,
            color:Boolean, unicode:Boolean,
            arguments:List<String>, tasks:List<Array<String>>,
            debugPort:Int, debugConfig: DebugScheme,
            pty:Boolean) : Pair<Process, String> {
        val env = HashMap<String, String>()
        if (options.passParentEnvironmentVariables) {
            env.putAll(EnvironmentUtil.getEnvironmentMap())
        }
        env["WEMI_COLOR"] = color.toString()
        env["WEMI_UNICODE"] = unicode.toString()
        env.putAll(options.environmentVariables)

        val commandLine = ArrayList<String>()
        if (options.javaExecutable.isNotBlank()) {
            commandLine.add(options.javaExecutable)
        } else {
            commandLine.add("java")
        }
        commandLine.addAll(options.javaOptions)

        when (debugConfig) {
            DebugScheme.WEMI_BUILD_SCRIPTS -> {
                commandLine.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=$debugPort")
            }
            DebugScheme.WEMI_FORKED_PROCESSES -> {
                env["WEMI_RUN_DEBUG_PORT"] = debugPort.toString()
            }
            DebugScheme.DISABLED -> {}
        }

        commandLine.add("-jar")
        commandLine.add(file.toAbsolutePath().toString())

        commandLine.addAll(arguments)

        for ((i, task) in tasks.withIndex()) {
            commandLine.addAll(task)
            if (i + 1 < tasks.size) {
                commandLine.add(";")
            }
        }

        if (pty) {
            val builder = PtyProcessBuilder()
            builder.setDirectory(file.toAbsolutePath().parent.toString())
            builder.setCygwin(true)
            builder.setEnvironment(env)
            builder.setWindowsAnsiColorEnabled(false) // We don't emit those
            builder.setCommand(commandLine.toTypedArray())
            builder.setRedirectErrorStream(false)
            builder.setConsole(true)
            return builder.start() to commandLine.joinToString(" ")
        } else {
            val builder = ProcessBuilder()
            builder.directory(file.toAbsolutePath().parent.toFile())
            builder.environment().putAll(env)
            builder.command(commandLine)
            builder.redirectInput(ProcessBuilder.Redirect.PIPE)
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            builder.redirectError(ProcessBuilder.Redirect.PIPE)
            return builder.start() to commandLine.joinToString(" ")
        }
    }

    /**
     * @param pty whether or not a [com.pty4j.PtyProcess] should be created. This is needed for Intellij terminal, but
     *            its implementation could be buggy for some byte-sensitive operations
     */
    fun createWemiProcess(options: WemiLauncherOptions,
                                  color:Boolean, unicode:Boolean,
                                  arguments:List<String>, tasks:List<Array<String>>,
                                  debugPort:Int, debugConfig: DebugScheme,
                                  pty:Boolean) : Pair<Process, String> {
        if (versionPre010) {
            return pre010_createWemiProcess(options, color, unicode, arguments, tasks, debugPort, debugConfig, pty)
        }

        val env = HashMap<String, String>()
        if (options.passParentEnvironmentVariables) {
            env.putAll(EnvironmentUtil.getEnvironmentMap())
        }
        env["WEMI_COLOR"] = color.toString()
        env["WEMI_UNICODE"] = unicode.toString()
        env.putAll(options.environmentVariables)
        if (options.javaExecutable.isNotBlank()) {
            env["WEMI_JAVA"] = options.javaExecutable
        }
        if (options.javaOptions.isNotEmpty()) {
            env["WEMI_JAVA_OPTS"] = options.javaOptions.joinToString(" ")
        }

        val commandLine = ArrayList<String>()
        if (SystemInfo.isWindows) {
            commandLine.add(windowsShellExecutable)
        }

        commandLine.add(file.toAbsolutePath().toString())

        when (debugConfig) {
            DebugScheme.WEMI_BUILD_SCRIPTS -> {
                commandLine.add("--debug-suspend=$debugPort")
            }
            DebugScheme.WEMI_FORKED_PROCESSES -> {
                env["WEMI_RUN_DEBUG_PORT"] = debugPort.toString()
            }
            DebugScheme.DISABLED -> {}
        }

        commandLine.addAll(arguments)

        for ((i, task) in tasks.withIndex()) {
            commandLine.addAll(task)
            if (i + 1 < tasks.size) {
                commandLine.add(";")
            }
        }

        if (pty) {
            val builder = PtyProcessBuilder()
            builder.setDirectory(file.toAbsolutePath().parent.toString())
            builder.setCygwin(true)
            builder.setEnvironment(env)
            builder.setWindowsAnsiColorEnabled(false) // We don't emit those
            builder.setCommand(commandLine.toTypedArray())
            builder.setRedirectErrorStream(false)
            builder.setConsole(true)
            return builder.start() to commandLine.joinToString(" ")
        } else {
            val builder = ProcessBuilder()
            builder.directory(file.toAbsolutePath().parent.toFile())
            builder.environment().putAll(env)
            builder.command(commandLine)
            builder.redirectInput(ProcessBuilder.Redirect.PIPE)
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            builder.redirectError(ProcessBuilder.Redirect.PIPE)
            return builder.start() to commandLine.joinToString(" ")
        }
    }

    fun getClasspathSourceEntries(options:WemiLauncherOptions):List<Path> {
        if (versionPre010) {
            try {
                ZipFile(file.toFile()).use { zipFile ->
                    val sourceEntry = zipFile.getEntry("source.zip")
                    if (sourceEntry != null) {
                        val sourcesPath = file.parent.resolve("build/cache/wemi-libs-ide/wemi-source.jar")

                        // Extract sources
                        zipFile.getInputStream(sourceEntry).use { ins ->
                            Files.copy(ins, sourcesPath)
                        }

                        return listOf(sourcesPath.toAbsolutePath())
                    }
                }
            } catch (t:Throwable) {
                LOG.warn("Failed to retrieve Wemi sources", t)
                return emptyList()
            }
        }

        return wemiHome(options)?.let { wemiHome ->
            try {
                Files.list(wemiHome.resolve("sources")).collect(Collectors.toList())
            } catch (e: IOException) {
                LOG.warn("Failed to resolve Wemi sources", e)
                null
            }
        } ?: emptyList()
    }

    private companion object {
        private val LOG = Logger.getInstance(WemiLauncher::class.java)
    }
}

interface SessionActivityTracker {
    fun stageBegin(name:String)
    fun stageEnd()

    fun taskBegin(name:String)
    fun taskEnd(output:String?, success:Boolean)

    fun sessionOutput(text:String, outputType: Key<*>)
}

inline fun <T> SessionActivityTracker.stage(name:String, action:()->T):T {
    stageBegin(name)
    try {
        return action()
    } finally {
        stageEnd()
    }
}

class WemiLauncherSession(
        val launcher: WemiLauncher,
        val options: WemiLauncherOptions,
        private val createProcess: () -> Process,
        private val prefixConfigurations: List<String> = emptyList(),
        private val tracker: SessionActivityTracker?,
        private val sessionState: SessionState) : SessionState.Listener {

    var wemiVersion: Version = Version.NONE
    private var process = SessionProcess(createProcess(), tracker)

    private val jsonPrettyPrintSettings = JsonValue.PrettyPrintSettings().apply {
        outputType = OutputType.json
    }

    fun task(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):Result {
        sessionState.checkCancelled()
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

        var jsonResult:String? = null
        var success = false
        try {
            if (process.isDead()) {
                tracker?.taskBegin("Restarting...")
                try {
                    process.kill()
                    process = SessionProcess(createProcess(), tracker)
                } finally {
                    tracker?.taskEnd(null, !process.isDead())
                }
            }

            tracker?.taskBegin(taskPath.toString())
            try {
                val taskResult = process.task(taskPath)
                return taskResult.use({ taskDataCharArray ->
                    try {
                        val json = JsonReader().parse(taskDataCharArray, 0, taskDataCharArray.size)
                        jsonResult = try {
                            json.prettyPrint(jsonPrettyPrintSettings)
                        } catch (e: Exception) {
                            "Pretty print failed:\n$e"
                        }
                        success = true
                        Result(ResultStatus.SUCCESS, json)
                    } catch (e: JsonException) {
                        LOG.error("Failed to parse json", e)
                        val taskDataString = String(taskDataCharArray)
                        LOG.info("Broken json:\n$taskDataString")
                        jsonResult = taskDataString
                        Result(ResultStatus.CORRUPTED_RESPONSE_FORMAT, null)
                    }
                }, { errorCode ->
                    val status = statusForNumber(errorCode)
                    jsonResult = status.name
                    Result(status, null)
                })
            } catch (e:Exception) {
                if (jsonResult == null) {
                    val writer = StringWriter()
                    PrintWriter(writer).use { e.printStackTrace(it) }
                    jsonResult = writer.toString()
                }
                throw e
            }
        } finally {
            tracker?.taskEnd(jsonResult, success)
        }
    }

    override fun sessionStateChange(newState: SessionState.State) {
        when (newState) {
            SessionState.State.CANCELLED -> process.process.destroy()
            SessionState.State.CANCELLED_FORCE -> process.process.destroyForcibly()
            else -> {}
        }
    }

    init {
        sessionState.addListener(this)
    }

    fun close() {
        sessionState.removeListener(this)
        sessionState.finish(process.kill())
    }

    class SessionProcess(val process:Process, private val tracker: SessionActivityTracker?) {

        private val output = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

        private val taskOutputsLock = Object()
        private var taskOutputsGenerated = true
        private val taskOutputs = ArrayDeque<CharArray>(16)

        init {
            ProcessIOExecutorService.INSTANCE.submit {
                ConcurrencyUtil.runUnderThreadName("Wemi SessionProcess output reader ($process)") {
                    try {
                        process.inputStream.reader(Charsets.UTF_8).use { reader ->
                            val outputWriter = CharArrayWriter(2048)

                            fun flushOutputWriter() {
                                synchronized(taskOutputsLock) {
                                    taskOutputs.addLast(outputWriter.toCharArray())
                                    taskOutputsLock.notify()
                                }
                                outputWriter.reset()
                            }

                            val buffer = CharArray(1024)
                            while (true) {
                                val read = reader.read(buffer)
                                if (read < 0) {
                                    break
                                }
                                if (read == 0) {
                                    continue
                                }
                                var partStart = 0
                                var partEnd = 0
                                while (partEnd < read) {
                                    if (buffer[partEnd] == '\u0000') {
                                        outputWriter.write(buffer, partStart, partEnd)
                                        flushOutputWriter()
                                        partStart = partEnd + 1
                                        partEnd = partStart
                                        continue
                                    }
                                    partEnd++
                                }
                                if (partStart < partEnd) {
                                    outputWriter.write(buffer, partStart, partEnd)
                                }
                            }
                        }
                    } finally {
                        synchronized(taskOutputsLock) {
                            taskOutputsGenerated = false
                            taskOutputs.addLast(charArrayOf())
                            taskOutputsLock.notify()
                        }
                    }
                }
            }
            ProcessIOExecutorService.INSTANCE.submit {
                ConcurrencyUtil.runUnderThreadName("Wemi SessionProcess error reader ($process)") {
                    process.errorStream.reader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(1024)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) {
                                break
                            }
                            if (read == 0) {
                                continue
                            }
                            tracker?.sessionOutput(String(buffer, 0, read), ProcessOutputTypes.STDERR)
                        }
                    }
                }
            }
        }

        fun kill():Int {
            StreamUtil.closeStream(output)

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                LOG.warn("Session $this does not want to end, shutting down")
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    LOG.warn("Session $this is probably frozen, forcibly shutting down")
                    process.destroyForcibly()
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        LOG.warn("Session $this is frozen and can't be shut down")
                        return -2
                    }
                }
            }

            return try { process.exitValue() } catch (e:IllegalThreadStateException) { -1 }
        }

        fun isDead():Boolean = !process.isAlive

        fun task(task: CharSequence): Failable<CharArray, Int> {
            try {
                // Read extra data/output in buffers
                synchronized(taskOutputsLock) {
                    while (taskOutputs.isNotEmpty()) {
                        tracker?.sessionOutput(String(taskOutputs.removeFirst()), ProcessOutputTypes.STDOUT)
                    }
                }

                // Write task command
                output.append(task).append('\n')
                output.flush()

                val result = synchronized(taskOutputsLock) {
                    while (taskOutputs.isEmpty() && taskOutputsGenerated) {
                        taskOutputsLock.wait()
                    }
                    return@synchronized if (taskOutputs.isNotEmpty()) {
                        taskOutputs.removeFirst()
                    } else {
                        null
                    }
                }

                return if (result != null) {
                    Failable.success(result)
                } else {
                    // No task outputs are generated, this process has died or closed output streams. Kill it!
                    // (tracker will be notified elsewhere)
                    Failable.failure(kill())
                }
            } catch (e:Exception) {
                LOG.error("Error while evaluating task '$task'", e)
                throw e
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WemiLauncherSession::class.java)

        data class Result (val status:ResultStatus, val data:JsonValue?)

        enum class ResultStatus(val number:Int) {
            SUCCESS(0),
            UNKNOWN_ERROR(1),
            ARGUMENT_ERROR(2),
            BUILD_SCRIPT_COMPILATION_ERROR(3),
            MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR(4),
            MACHINE_OUTPUT_NO_PROJECT_ERROR(5),
            MACHINE_OUTPUT_NO_CONFIGURATION_ERROR(6),
            MACHINE_OUTPUT_NO_KEY_ERROR(7),
            MACHINE_OUTPUT_KEY_NOT_SET_ERROR(8),

            CORRUPTED_RESPONSE_FORMAT(-1)
        }

        fun statusForNumber(number: Int):ResultStatus {
            return ResultStatus.values().find { it.number == number } ?: ResultStatus.UNKNOWN_ERROR
        }
    }
}
