package com.darkyen.wemi.intellij

import com.darkyen.wemi.intellij.file.isWemiLauncher
import com.darkyen.wemi.intellij.file.isWemiScriptSource
import com.darkyen.wemi.intellij.options.RunOptions
import com.darkyen.wemi.intellij.options.WemiLauncherOptions
import com.darkyen.wemi.intellij.util.OSProcessHandlerForWemi
import com.darkyen.wemi.intellij.util.Version
import com.darkyen.wemi.intellij.util.collectOutputAndKill
import com.darkyen.wemi.intellij.util.executable
import com.darkyen.wemi.intellij.util.toPath
import com.esotericsoftware.jsonbeans.JsonException
import com.esotericsoftware.jsonbeans.JsonReader
import com.esotericsoftware.jsonbeans.JsonValue
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessIOExecutorService
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.StreamUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ConcurrencyUtil
import com.intellij.util.EnvironmentUtil
import com.pty4j.PtyProcessBuilder
import java.io.ByteArrayOutputStream
import java.io.CharArrayWriter
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.zip.ZipFile

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

/** Finds project's Wemi launcher, if present.
 * Should be fairly cheap. */
@Deprecated("")
fun findWemiLauncher(project:Project):WemiLauncher? {
    if (project.isDefault)
        return null
    val wemiJar = project.guessProjectDir().toPath()?.resolve(WemiLauncherFileName)?.toAbsolutePath() ?: return null

    if (!Files.isRegularFile(wemiJar)) return null

    return WemiLauncher(wemiJar)
}

fun findWemiLauncher(projectDir:Path):WemiLauncher? {
    val wemiJar = projectDir.resolve(WemiLauncherFileName).toAbsolutePath()

    if (!Files.isRegularFile(wemiJar)) return null

    try {
        if (!wemiJar.executable) {
            return null
        }
    } catch (e : Exception) {
        // Filesystem may not support the executable permission
    }

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

    private fun wemiHome(options: WemiLauncherOptions):Path? {
        if (versionPre010) {
            return null
        }

        return try {
            val process = createWemiProcessBuilder(options, listOf("--print-wemi-home"), emptyList(), -1, DebugScheme.DISABLED).first.start()
            StreamUtil.closeStream(process.outputStream)
            val result = process.collectOutputAndKill(10, TimeUnit.SECONDS).toString()
            if (result.isNotBlank()) {
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

    private fun pre010_createWemiProcessBuilder(
            options: WemiLauncherOptions,
            arguments:List<String>, tasks:List<Array<String>>,
            debugPort:Int, debugConfig: DebugScheme) : Pair<PtyProcessBuilder, String> {
        val env = HashMap<String, String>()
        if (options.passParentEnvironmentVariables) {
            env.putAll(EnvironmentUtil.getEnvironmentMap())
        }
        env["WEMI_COLOR"] = "true"
        env["WEMI_UNICODE"] = "true"
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

        val builder = PtyProcessBuilder()
        builder.setDirectory(file.toAbsolutePath().parent.toString())
        builder.setCygwin(true)
        builder.setEnvironment(env)
        builder.setWindowsAnsiColorEnabled(false) // We don't emit those
        builder.setCommand(commandLine.toTypedArray())
        builder.setRedirectErrorStream(false)
        builder.setConsole(true)
        return builder to commandLine.joinToString(" ")
    }

    fun createWemiProcessBuilder(options: WemiLauncherOptions,
                          arguments:List<String>, tasks:List<Array<String>>,
                          debugPort:Int, debugConfig: DebugScheme) : Pair<PtyProcessBuilder, String> {
        if (versionPre010) {
            return pre010_createWemiProcessBuilder(options, arguments, tasks, debugPort, debugConfig)
        }

        val env = HashMap<String, String>()
        if (options.passParentEnvironmentVariables) {
            env.putAll(EnvironmentUtil.getEnvironmentMap())
        }
        env["WEMI_COLOR"] = "true"
        env["WEMI_UNICODE"] = "true"
        env.putAll(options.environmentVariables)
        if (options.javaExecutable.isNotBlank()) {
            env["WEMI_JAVA"] = options.javaExecutable
        }
        if (options.javaOptions.isNotEmpty()) {
            env["WEMI_JAVA_OPTS"] = options.javaOptions.joinToString(" ")
        }

        val commandLine = ArrayList<String>()
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

        val builder = PtyProcessBuilder()
        builder.setDirectory(file.toAbsolutePath().parent.toString())
        builder.setCygwin(true)
        builder.setEnvironment(env)
        builder.setWindowsAnsiColorEnabled(false) // We don't emit those
        builder.setCommand(commandLine.toTypedArray())
        builder.setRedirectErrorStream(false)
        builder.setConsole(true)
        //return OSProcessHandlerForWemi(builder.start(), commandLine.joinToString(" "))
        return builder to commandLine.joinToString(" ")
    }

    fun createWemiProcessHandler(options:WemiLauncherOptions,
                                 debugPort:Int, debugConfig:DebugScheme,
                                 allowBrokenBuildScripts:Boolean,
                                 interactive:Boolean,
                                 machineReadable:Boolean):OSProcessHandler {
        val arguments = ArrayList<String>()

        if (allowBrokenBuildScripts) {
            arguments.add("--allow-broken-build-scripts")
        }
        if (interactive) {
            arguments.add("--interactive")
        }
        if (machineReadable) {
            arguments.add("--machine-readable-output")
        }

        // TODO(jp): Instanceof is a dangerous business
        val tasks = if (options is RunOptions) options.tasks else emptyList()

        val (builder, commandLine) = createWemiProcessBuilder(options, arguments, tasks, debugPort, debugConfig)
        return OSProcessHandlerForWemi(builder.start(), commandLine)
    }

    fun createWemiProcess(options:WemiLauncherOptions,
                                 debugPort:Int, debugConfig:DebugScheme,
                                 allowBrokenBuildScripts:Boolean,
                                 interactive:Boolean,
                                 machineReadable:Boolean):Process {
        val arguments = ArrayList<String>()

        if (allowBrokenBuildScripts) {
            arguments.add("--allow-broken-build-scripts")
        }
        if (interactive) {
            arguments.add("--interactive")
        }
        if (machineReadable) {
            arguments.add("--machine-readable-output")
        }

        // TODO(jp): Instanceof is a dangerous business
        val tasks = if (options is RunOptions) options.tasks else emptyList()

        return createWemiProcessBuilder(options, arguments, tasks, debugPort, debugConfig).first.start()
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
            Files.list(wemiHome.resolve("sources")).collect(Collectors.toList())
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
    fun taskEnd()

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
        val launcher:WemiLauncher,
        val options:WemiLauncherOptions,
        private val createProcess: () -> Process,
        private val prefixConfigurations: List<String> = emptyList(),
        private val tracker: SessionActivityTracker?) : Closeable {

    var wemiVersion: Version = Version.NONE
    private var process = SessionProcess(createProcess(), tracker)

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
                tracker?.taskBegin("Restarting...")
                try {
                    process.close()
                    process = SessionProcess(createProcess(), tracker)
                } finally {
                    tracker?.taskEnd()
                }
            }

            tracker?.taskBegin(taskPath.toString())
            val taskData = CharArrayWriter()
            val taskResult = process.task(taskPath, taskData)

            val jsonReader = JsonReader()
            val taskDataCharArray = taskData.toCharArray()!!
            try {
                val json = jsonReader.parse(taskDataCharArray, 0, taskDataCharArray.size)
                return Result(statusForNumber(taskResult), json)
            } catch (e:JsonException) {
                LOG.error("Failed to parse json", e)
                LOG.debug("Broken json:\n${String(taskDataCharArray)}")
                return Result(ResultStatus.CORRUPTED_RESPONSE_FORMAT, null)
            }
        } finally {
            tracker?.taskEnd()
        }
    }

    override fun close() {
        process.close()
    }

    private class SessionProcess(val process:Process, private val tracker: SessionActivityTracker?) {

        private val output = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

        init {
            ProcessIOExecutorService.INSTANCE.submit {
                ConcurrencyUtil.runUnderThreadName("Wemi SessionProcess output reader ($process)") {
                    readStreamContinuously(process.inputStream, true)
                }
            }
            ProcessIOExecutorService.INSTANCE.submit {
                ConcurrencyUtil.runUnderThreadName("Wemi SessionProcess error reader ($process)") {
                    readStreamContinuously(process.errorStream, false)
                }
            }
        }

        private fun readStreamContinuously(stream: InputStream, output:Boolean) {
            try {
                stream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) {
                            break
                        } else if (read > 0) {
                            onProcessOutput(buffer, read, output)
                        }
                    }
                }
            } finally {
                // Nothing will ever come from this, so don't block, ever
                waitingForTaskSemaphore.release(1000)
            }
        }

        @Volatile
        private var taskOutput:Writer? = null
        private val waitingForTaskSemaphore = Semaphore(0, false)

        private fun onProcessOutput(data:CharArray, dataSize:Int, output:Boolean) {
            if (!output) {
                tracker?.sessionOutput(String(data, 0, dataSize), ProcessOutputTypes.STDERR)
                return
            }

            var extraBegin = 0
            val taskOutput = this.taskOutput
            if (taskOutput != null) {
                var end = dataSize
                var done = false

                for (i in 0 until dataSize) {
                    if (data[i] == 0.toChar()) {
                        end = i
                        done = true
                        break
                    }
                }

                taskOutput.write(data, 0, end)
                if (done) {
                    this.taskOutput = null
                    waitingForTaskSemaphore.release()
                }

                if (end >= dataSize) {
                    return
                }
                extraBegin = end
            }

            tracker?.sessionOutput(String(data, extraBegin, dataSize - extraBegin), ProcessOutputTypes.STDOUT)
        }

        fun close():Int {
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

        fun task(task: CharSequence, taskData: Writer):Int {
            try {
                // Read extra data/output in buffers
                this.taskOutput = taskData

                output.append(task).append('\n')
                output.flush()

                waitingForTaskSemaphore.acquire()

                return if (this.taskOutput == null) {
                    ResultStatus.SUCCESS.number
                } else {
                    // Semaphore was released because the stream was ended, not becauase the request completed
                    close()
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
