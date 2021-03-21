package wemi.run

import org.slf4j.LoggerFactory
import wemi.PrettyPrinter
import wemi.WithExitCode
import wemi.boot.CLI
import wemi.boot.MachineReadableFormatter
import wemi.boot.MachineReadableOutputFormat
import wemi.boot.newMachineReadableJsonPrinter
import wemi.boot.writeShellEscaped
import wemi.util.CliStatusDisplay.Companion.withStatus
import wemi.util.Color
import wemi.util.absolutePath
import wemi.util.field
import wemi.util.format
import wemi.util.writeMap
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Writer
import java.nio.file.Path
import java.util.concurrent.ForkJoinPool

private val LOG = LoggerFactory.getLogger("Run")

fun prepareJavaProcessCommand(javaExecutable: Path, classpath: Collection<Path>,
                              mainClass: String, javaOptions: Collection<String>, args: Collection<String>): List<String> {
    val command = ArrayList<String>()
    command.add(javaExecutable.absolutePath)
    command.add("-cp")
    command.add(classpath.joinToString(System.getProperty("path.separator", ":")))
    command.addAll(javaOptions)
    command.add(mainClass)
    command.addAll(args)

    return command
}

/**
 * Prepare [ProcessBuilder] for java JVM launch.
 *
 * @param javaExecutable to be used
 * @param workingDirectory in which the process should run
 * @param classpath of the new JVM
 * @param mainClass to launch
 * @param javaOptions additional raw options for JVM
 * @param args for the launched program
 * @param environment variables for the created process, or null to use default
 */
fun prepareJavaProcess(javaExecutable: Path, workingDirectory: Path,
                       classpath: Collection<Path>, mainClass: String, javaOptions: Collection<String>,
                       args: Collection<String>,
                       environment:Map<String, String>? = null): ProcessBuilder {
    val command = prepareJavaProcessCommand(javaExecutable, classpath, mainClass, javaOptions, args)
    LOG.debug("Prepared command {} in {}", command, workingDirectory)
    val builder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .inheritIO()
    if (environment != null) {
        val env = builder.environment()
        try {
            env.clear()
        } catch (e:Exception) {
            LOG.warn("Failed to clear the default process environment", e)
        }
        for ((key, value) in environment) {
            try {
                env[key] = value
            } catch (e:Exception) {
                LOG.warn("Failed to set environment variable '{}'='{}'", key, value, e)
            }
        }
    }
    return builder
}

private val LOG_STDOUT = LoggerFactory.getLogger("stdout")
private val LOG_STDERR = LoggerFactory.getLogger("stderr")

/**
 * Create a process from [builder] and run it to completion on CLI foreground.
 * Signals are forwarded to the process as well.
 * @param controlOutput if false, input/output/error will be logged instead of inherited
 */
fun runForegroundProcess(builder:ProcessBuilder, separateOutputByNewlines:Boolean = true, controlOutput:Boolean = true, logStdOutLine:(String)->Boolean = { true }, logStdErrLine:(String)->Boolean = { true }):Int {
    // Separate process output from Wemi output
    return CLI.MessageDisplay.withStatus(false) {
        if (separateOutputByNewlines) println()
        if (controlOutput) {
            builder.redirectError(ProcessBuilder.Redirect.PIPE)
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        }
        val process = builder.start()
        if (controlOutput) {
            val commonPool = ForkJoinPool.commonPool()
            commonPool.submit {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use {
                    while (true) {
                        val line: String = it.readLine() ?: break
                        if (logStdErrLine(line)) {
                            LOG_STDERR.info("{}", line)
                        }
                    }
                }
            }
            commonPool.submit {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use {
                    while (true) {
                        val line: String = it.readLine() ?: break
                        if (logStdOutLine(line)) {
                            LOG_STDOUT.info("{}", line)
                        }
                    }
                }
            }
        }
        val result = CLI.forwardSignalsTo(process) { process.waitFor() }
        if (separateOutputByNewlines) println()
        result
    }
}

/** Wrapper for exit code integer that implements [WithExitCode]. */
class ExitCode(val value:Int): WithExitCode {
    override fun processExitCode(): Int = value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExitCode) return false

        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        return value
    }

    override fun toString(): String {
        return value.toString()
    }
}

internal val ProcessBuilderPrettyPrinter:PrettyPrinter<ProcessBuilder> = { pb: ProcessBuilder, _: Int ->
    val sb = StringBuilder()
    sb.format(Color.Blue).append((pb.directory() ?: File(".")).normalize().path).append('\n')
    for ((key, value) in pb.environment() ?: emptyMap()) {
        sb.append("   ").format(Color.Green).append(key).format(Color.White).append(" = '").format(Color.Blue).append(value).format(Color.White).append("'\n")
    }
    val command = pb.command()
    if (command.size > 0) {
        sb.format(Color.Black).append(command[0]).format(Color.Blue)
        for (i in 1 until command.size) {
            sb.append(' ').append(command[i])
        }
    } else {
        sb.format(Color.Red).append("<No command>")
    }
    sb.format().append('\n').toString()
}

/**
 * This is a bit hairy, but let's be strict, for the sake of security.
 * https://stackoverflow.com/q/2821043
 */
private fun isSafeEnvironmentVariable(name:String):Boolean {
    if (name.isEmpty()) {
        return false
    }
    val first = name[0]
    if (first !in 'a'..'z' && first !in 'A'..'Z' && first != '_') {
        return false
    }
    for (i in 1 until name.length) {
        val c = name[i]
        if (c !in 'a'..'z' && c !in 'A'..'Z' && c != '_' && c !in '0'..'9') {
            return false
        }
    }
    return true
}

internal val EscapedStringListShellFormatter:MachineReadableFormatter<List<String>> = formatter@{ format: MachineReadableOutputFormat, list:List<String>, out:Writer ->
    if (format != MachineReadableOutputFormat.SHELL) {
        return@formatter false
    }

    if (list.isNotEmpty()) {
        out.writeShellEscaped(list[0], true)
        for (i in 1 until list.size) {
            out.append(' ').writeShellEscaped(list[i], true)
        }
    }

    true
}

internal val EnvVarMachineReadableFormatter:MachineReadableFormatter<Map<String, String>> = { format: MachineReadableOutputFormat, processEnv: Map<String, String>, out: Writer ->
    when (format) {
        MachineReadableOutputFormat.JSON -> {
            newMachineReadableJsonPrinter(out).writeMap(String::class.java, String::class.java, processEnv)
            true
        }
        MachineReadableOutputFormat.SHELL -> {
            // Diff shell to determine what should be added (but ignore removals)
            val currentEnv = System.getenv()
            for ((key, value) in processEnv) {
                val currentValue = currentEnv[key]
                if (value == currentValue) {
                    continue
                }

                // Check whether key is valid and skip it if it isn't, because it could be a security risk otherwise
                if (isSafeEnvironmentVariable(key)) {
                    out.write(key)
                    out.append('=')
                    out.writeShellEscaped(value, true)
                    out.append(' ')
                } else {
                    LOG.warn("Omitting environment variable '{}={}' from output, because its name is suspicious", key, value)
                }
            }
            true
        }
    }
}

internal val ProcessBuilderMachineReadableFormatter:MachineReadableFormatter<ProcessBuilder> = { format: MachineReadableOutputFormat, pb: ProcessBuilder, out: Writer ->
    when (format) {
        MachineReadableOutputFormat.JSON -> {
            val jsonWriter = newMachineReadableJsonPrinter(out)
            jsonWriter.`object`()
            jsonWriter.field("directory", (pb.directory() ?: File(".")).absoluteFile.normalize().path)
            jsonWriter.field("environment", pb.environment() ?: emptyMap())
            jsonWriter.field("command", pb.command() ?: emptyList())
            jsonWriter.pop()
            true
        }
        MachineReadableOutputFormat.SHELL -> {
            // Directory and environment is ignored because it is not straightforward to actually use in a shell
            // You can call runEnvironment and runDirectory if you want this information anyway
            EscapedStringListShellFormatter(MachineReadableOutputFormat.SHELL, pb.command() ?: emptyList(), out)
        }
    }
}