package wemi.compile.impl

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.boot.WemiBuildFileExtensions
import wemi.boot.WemiBuildScript
import wemi.compile.*
import wemi.compile.KotlinCompiler.CompileExitStatus.*
import wemi.util.*
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KMutableProperty0

@Suppress("unused", "ClassName")
/**
 * Kotlin compiler interface implementation, DO NOT TOUCH FROM OTHER CLASSES THAN [KotlinCompiler]!!!
 */
internal class KotlinCompilerImpl1_1_4_3 : KotlinCompiler {

    private fun <T> KMutableProperty0<T>.ensure(expected:T){
        val value = this.get()
        if (value != expected) {
            LOG.warn("Setting ${this.name} to $value has no effect, as this property is managed by Wemi (reset to $expected)")
            this.set(expected)
        }
    }

    override fun compileJVM(sources: Collection<LocatedFile>, classpath: Collection<Path>, destination: Path, flags: CompilerFlags, logger: Logger, loggerMarker: Marker?): KotlinCompiler.CompileExitStatus {
        val messageCollector = createLoggingMessageCollector(logger, loggerMarker)
        val compiler = K2JVMCompiler()
        val args = compiler.createArguments()

        // Prepare defaults for later check
        val originalJvmTarget = args.jvmTarget
        val originalLanguageVersion = args.languageVersion
        val originalAPIVersion = args.apiVersion

        // Load free args
        flags.use(KotlinCompilerFlags.customFlags) {
            compiler.parseArguments(it.toTypedArray(), args)
        }

        // Check that free args did not modify anything
        if (args.freeArgs.isNotEmpty()) {
            LOG.warn("Setting freeArgs to ${args.freeArgs} has no effect, as this property is managed by Wemi (cleared)")
            args.freeArgs.clear()
        }

        args::destination.ensure(null)
        args::classpath.ensure(null)
        args::jdkHome.ensure(null)
        args::noStdlib.ensure(false)
        args::noReflect.ensure(false)
        args::script.ensure(false)
        args::scriptTemplates.ensure(null)
        args::moduleName.ensure(null)
        args::jvmTarget.ensure(originalJvmTarget)
        args::scriptResolverEnvironment.ensure(null)
        args::buildFile.ensure(null)

        args::useJavac.ensure(false)
        args::javacArguments.ensure(null)

        args::languageVersion.ensure(originalLanguageVersion)
        args::apiVersion.ensure(originalAPIVersion)
        args::kotlinHome.ensure(null)
        args::pluginOptions.ensure(null)
        args::pluginClasspaths.ensure(null)

        // Setup args
        val sourceSet = HashSet<String>()
        val compilingWemiBuildFiles = flags.useDefault(KotlinJVMCompilerFlags.compilingWemiBuildFiles, false)
        for (source in sources) {
            val file = source.file
            when {
                file.isDirectory() ||
                        file.name.pathHasExtension(KotlinSourceFileExtensions) ||
                        (compilingWemiBuildFiles && file.name.pathHasExtension(WemiBuildFileExtensions)) -> {
                    sourceSet.add(file.absolutePath)
                }
                file.name.pathHasExtension(JavaSourceFileExtensions) -> {
                    sourceSet.add(source.root.absolutePath)// Add folder
                }
                else -> {
                    LOG.warn("Unrecognized source file, ignoring: {}", file)
                }
            }
        }
        args.freeArgs.addAll(sourceSet)
        args.destination = destination.absolutePath
        args.classpath = classpath.joinToString(separator = File.pathSeparator) { it.absolutePath }
        flags.use(KotlinJVMCompilerFlags.jdkHome) {
            args.jdkHome = it
        }
        args.noStdlib = true
        args.noReflect = true
        flags.use(KotlinCompilerFlags.moduleName) {
            args.moduleName = it
        }
        flags.use(KotlinJVMCompilerFlags.jvmTarget) {
            args.jvmTarget = it.string
        }

        flags.use(KotlinCompilerFlags.languageVersion) {
            args.languageVersion = it
        }
        flags.use(KotlinCompilerFlags.apiVersion) {
            args.apiVersion = it
        }
        val pluginOptions = ArrayList<String>()
        val pluginClasspath = ArrayList<String>()
        flags.use(KotlinCompilerFlags.pluginOptions) {
            pluginOptions.addAll(it)
        }
        flags.use(KotlinCompilerFlags.pluginClasspath) {
            pluginClasspath.addAll(it)
        }
        if (compilingWemiBuildFiles) {
            // Hack to recognize .wemi files as kotlin files
            // This has to be done as a "plugin" because that seems to be the only way to get hold of
            // the file type registry before it is too late. By masquerading as a plugin, we get to
            // execute arbitrary code when the FileTypeRegistry global is properly initialized,
            // so we can register our type.

            // This adds RegisterWemiFilesAsKotlinFilesInCompiler compiler plugin
            pluginClasspath.add(WemiBuildScript!!.wemiLauncherJar.absolutePath)
        }
        args.pluginOptions = pluginOptions.toTypedArray()
        args.pluginClasspaths = pluginClasspath.toTypedArray()

        // Compile
        val exitCode = compiler.exec(messageCollector, Services.EMPTY, args)

        // Done
        return when (exitCode) {
            ExitCode.OK -> OK
            ExitCode.COMPILATION_ERROR -> COMPILATION_ERROR
            ExitCode.INTERNAL_ERROR -> INTERNAL_ERROR
            ExitCode.SCRIPT_EXECUTION_ERROR -> INTERNAL_ERROR
        }
    }

    private fun createLoggingMessageCollector(log: Logger, marker: Marker? = null): MessageCollector {
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

    companion object {
        private val LOG = LoggerFactory.getLogger("KotlinCompilerBootstrap")
    }
}