package wemi.compile.impl

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.compile.KotlinCompiler
import java.io.File

private val LOG = LoggerFactory.getLogger("KotlinCompilerImpl")

@Suppress("unused")
/**
 * Kotlin compiler interface implementation, DO NOT TOUCH FROM OTHER CLASSES THAN [KotlinCompiler]!!!
 */
internal class KotlinCompilerImpl1_1_4 : KotlinCompiler {

    /**
     * @param sources kotlin files to be compiled
     * @param destination for generated class files, folder or .jar file
     * @param classpath for user class files
     * @param arguments custom arguments, parsed by kotlin compiler
     */
    override fun compile(sources: List<File>,
                         destination: File,
                         classpath: List<File>,
                         arguments: Array<String>,
                         logger: Logger,
                         loggerMarker: Marker?): KotlinCompiler.CompileExitStatus {

        val args = K2JVMCompilerArguments()

        val compiler = K2JVMCompiler()
        compiler.parseArguments(arguments, args)

        args.destination = destination.absolutePath
        args.classpath = classpath.map { it.absolutePath }.joinToString(":")

        args.freeArgs.clear()
        for (source in sources) {
            args.freeArgs.add(source.absolutePath)
        }

        LOG.trace("Invoking kotlin compiler with destination:{}, classpath:{}, freeArgs:{}", args.destination, args.classpath, args.freeArgs)

        val exitCode = compiler.exec(createLoggingMessageCollector(logger, loggerMarker), Services.EMPTY, args)
        return when (exitCode) {
            ExitCode.OK -> KotlinCompiler.CompileExitStatus.OK
            ExitCode.COMPILATION_ERROR -> KotlinCompiler.CompileExitStatus.COMPILATION_ERROR
            ExitCode.INTERNAL_ERROR -> KotlinCompiler.CompileExitStatus.INTERNAL_ERROR
            ExitCode.SCRIPT_EXECUTION_ERROR -> KotlinCompiler.CompileExitStatus.INTERNAL_ERROR
        }
    }

    fun createLoggingMessageCollector(log: Logger, marker: Marker? = null): MessageCollector {
        return object : MessageCollector {

            var hasErrors = false
            override fun clear() {
                // Their implementation doesn't clear errors, so, yeah
            }

            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
                hasErrors = hasErrors || severity.isError
                val renderer = MessageRenderer.PLAIN_RELATIVE_PATHS
                when (severity) {
                    CompilerMessageSeverity.EXCEPTION, CompilerMessageSeverity.ERROR -> {
                        if (!log.isErrorEnabled(marker)) {
                            return
                        }
                        log.error(marker, renderer.render(severity, message, location))
                    }
                    CompilerMessageSeverity.STRONG_WARNING, CompilerMessageSeverity.WARNING -> {
                        if (!log.isWarnEnabled(marker)) {
                            return
                        }
                        log.warn(marker, renderer.render(severity, message, location))
                    }
                    CompilerMessageSeverity.INFO, CompilerMessageSeverity.OUTPUT -> {
                        if (!log.isInfoEnabled(marker)) {
                            return
                        }
                        log.info(marker, renderer.render(severity, message, location))
                    }
                    CompilerMessageSeverity.LOGGING -> {
                        if (!log.isDebugEnabled(marker)) {
                            return
                        }
                        log.debug(marker, renderer.render(severity, message, location))
                    }
                    else -> {
                        log.error("Unsupported severity level: {}", severity)
                        log.error(marker, renderer.render(severity, message, location))
                    }
                }
            }

            override fun hasErrors(): Boolean = hasErrors

        }
    }
}