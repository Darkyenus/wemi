package wemi.compile.impl

import org.jetbrains.kotlin.build.JvmSourceRoot
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.boot.WemiBuildScript
import wemi.compile.*
import wemi.compile.KotlinCompiler.CompileExitStatus.*
import wemi.util.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KMutableProperty0

@Suppress("unused", "ClassName")
/**
 * Kotlin compiler interface implementation, DO NOT TOUCH FROM OTHER CLASSES THAN [KotlinCompiler]!!!
 */
internal class KotlinCompilerImpl1_2_21 : KotlinCompiler {

    /*
    Explicitly set flags from K2JVMCompilerArguments

    -d (destination)
    -classpath
    -jdk-home
    -no-stdlib (always true, we add it ourselves)
    -no-reflect (always true, we add it ourselves)
    -script (always false, we don't do scripts)
    -script-templates (always null)
    -module-name
    -jvm-target
    -script-resolver-environment (always null)
    -Xbuild-file (null)

    This all should be false/null, but consider using it in the future
    -Xuse-javac
    -Xcompile-java
    -XjavacArguments

    Explicitly set flags from CommonCompilerArguments
    -language-version
    -api-version
    -kotlin-home (null, not used)
    -P (plugin options)
    -Xplugin (plugin classpath)

    TODO Explicitly set mutliplatform flags
     */

    private fun <T> KMutableProperty0<T>.ensure(expected:T){
        val value = this.get()
        if (value != expected) {
            LOG.warn("Setting ${this.name} to $value has no effect, as this property is managed by Wemi (reset to $expected)")
            this.set(expected)
        }
    }

    override fun compileJVM(sources: Collection<LocatedFile>, classpath: Collection<Path>, destination: Path, cacheFolder: Path?, flags: CompilerFlags, logger: Logger, loggerMarker: Marker?): KotlinCompiler.CompileExitStatus {
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
        args::freeArgs.ensure(emptyList())

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
        args::compileJava.ensure(false)
        args::javacArguments.ensure(null)

        args::languageVersion.ensure(originalLanguageVersion)
        args::apiVersion.ensure(originalAPIVersion)
        args::kotlinHome.ensure(null)
        args::pluginOptions.ensure(null)
        args::pluginClasspaths.ensure(null)

        // Setup args
        val compilingWemiBuildFiles = flags.useDefault(KotlinJVMCompilerFlags.compilingWemiBuildFiles, false)
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
            args.jvmTarget = it
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
        var incremental = flags.useDefault(KotlinCompilerFlags.incremental, false)
        if (incremental && cacheFolder == null) {
            logger.warn(loggerMarker, "Disabling incremental compilation: null cache folder")
            incremental = false
        }

        val exitCode: ExitCode
        if (incremental) {
            val cachedOutput = cacheFolder!!.resolve("output")
            Files.createDirectories(cachedOutput)
            args.destination = cachedOutput.absolutePath

            val kotlincCache = cacheFolder.resolve("kotlinc-cache")
            Files.createDirectories(kotlincCache)
            val kotlincCacheFile = kotlincCache.toFile()

            val versions = commonCacheVersions(kotlincCacheFile) + standaloneCacheVersion(kotlincCacheFile)

            exitCode = withIC {
                val compilerRunner = IncrementalJvmCompilerRunner(
                        kotlincCacheFile,
                        sources.map { JvmSourceRoot(it.root.toFile(), null) }.toSet(),
                        versions, object : ICReporter {
                            override fun report(message: () -> String) {
                                logger.info(loggerMarker, "IC: {}", message())
                            }
                        },
                        usePreciseJavaTracking = true
                )
                compilerRunner.compile(sources.map { it.file.toFile() }, args, messageCollector, providedChangedFiles = null)
            }

            if (exitCode == ExitCode.OK) {
                // Copy kotlin output to real output
                cachedOutput.copyRecursively(destination)
            }
        } else {
            args.destination = destination.absolutePath
            args.freeArgs = sources.map { it.file.absolutePath }
            exitCode = compiler.exec(messageCollector, Services.EMPTY, args)
        }

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