package wemi.run

import org.slf4j.LoggerFactory
import wemi.boot.CLI
import wemi.boot.WemiRootFolder
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOG = LoggerFactory.getLogger("System")

/**
 * Create and run a process from [command], wait for its completion and return its output stream as [String].
 * The standard error and input stream are inherited, to allow interactive processes and to log any errors out.
 * The process is run through [wemi.boot.CLI.forwardSignalsTo], so it is user interruptible.
 *
 * @param command the command and its parameters, see [ProcessBuilder.command] for more info
 * @param workingDirectory of the process, [WemiRootFolder] by default
 * @param timeoutMs if the process takes more than this many milliseconds to complete, consider it frozen and forcefully kill it. No timeout by default
 * @param collectOutput if the function should collect the output (`true` by default). If `false`, the output is inherited, like the other two streams, and the result is considered `null`
 * @param outputCharset the charset of the output stream, when it is being collected. [Charsets.UTF_8] by default
 * @param onFail callback called when the process exits with a non-zero code. Its result is used as a result for this whole function. The parameter `code` is `null`, if the process is terminated due to [timeoutMs] and the `output` may be `null` as well
 * @return if [collectOutput] is `true` and the process exited cleanly with a zero code, the output of the process, as [String], with trailing whitespace (including new-lines) trimmed. Otherwise the result of [onFail]
 */
fun system(vararg command: String,
           workingDirectory: Path = WemiRootFolder,
           environment: Map<String, String> = emptyMap(),
           timeoutMs: Long = Long.MAX_VALUE,
           collectOutput: Boolean = true,
           outputCharset: Charset = Charsets.UTF_8,
           onFail: (code: Int?, output: String?) -> String? = { _, _ -> null }): String? {
	val process = ProcessBuilder(*command).run {
		directory(workingDirectory.toFile())
		redirectError(ProcessBuilder.Redirect.INHERIT)
		redirectInput(ProcessBuilder.Redirect.INHERIT)
		if (collectOutput) {
			redirectOutput(ProcessBuilder.Redirect.PIPE)
		} else {
			redirectOutput(ProcessBuilder.Redirect.INHERIT)
		}
		if (environment.isNotEmpty()) {
			environment().putAll(environment)
		}
		start()
	}

	val commandString = command.joinToString(" ")

	val result = if (collectOutput) {
		ForkJoinPool.commonPool().submit(Callable<String> {
			process.inputStream.reader(outputCharset).readText()
		})
	} else {
		null
	}

	if (!CLI.forwardSignalsTo(process) {
				if (timeoutMs == Long.MAX_VALUE) {
					process.waitFor(); true
				} else {
					process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
				}
			}) {
		LOG.error("Process $process ($commandString) timed out")
		process.destroyForcibly()
		val resultString = try {
			result?.get(5, TimeUnit.SECONDS)
		} catch (e: TimeoutException) {
			null
		}
		return onFail(null, resultString)
	}

	val resultString = result?.get()?.trimEnd()
	val exitValue = process.exitValue()
	if (exitValue != 0) {
		return onFail(exitValue, resultString)
	}

	return resultString
}
