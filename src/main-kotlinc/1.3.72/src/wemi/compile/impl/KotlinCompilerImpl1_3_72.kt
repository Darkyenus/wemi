package wemi.compile.impl

import org.jetbrains.kotlin.build.DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.arguments.validateArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.ICReporter
import org.jetbrains.kotlin.incremental.IncrementalJvmCompilerRunner
import org.jetbrains.kotlin.incremental.multiproject.EmptyModulesApiHistory
import org.jetbrains.kotlin.incremental.withIC
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.compile.CompilerFlags
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompiler.CompileExitStatus.*
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.compile.internal.MessageLocation
import wemi.compile.internal.render
import wemi.util.LocatedPath
import wemi.util.absolutePath
import wemi.util.copyRecursively
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KMutableProperty0

@Suppress("unused", "ClassName")
/**
 * Kotlin compiler interface implementation, DO NOT TOUCH FROM OTHER CLASSES THAN [KotlinCompiler]!!!
 */
internal class KotlinCompilerImpl1_3_72 : KotlinCompiler {

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

    override fun compileJVM(sources: Collection<LocatedPath>, classpath: Collection<Path>, destination: Path, cacheFolder: Path?, flags: CompilerFlags, logger: Logger, loggerMarker: Marker?): KotlinCompiler.CompileExitStatus {
        val messageCollector = createLoggingMessageCollector(logger, loggerMarker)
        val args = K2JVMCompilerArguments()

        // Prepare defaults for later check
        val originalJvmTarget = args.jvmTarget
        val originalLanguageVersion = args.languageVersion
        val originalAPIVersion = args.apiVersion

        // Load free args
        flags.use(KotlinCompilerFlags.customFlags) {
            // Based on org.jetbrains.kotlin.cli.common.CLITool.parseArguments
            parseCommandLineArguments(it, args)
            val message = validateArguments(args.errors)
            if (message != null) {
                throw IllegalArgumentException(message)
            }
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
        args.pluginOptions = pluginOptions.toTypedArray()
        args.pluginClasspaths = pluginClasspath.toTypedArray()

        // Compile
        var incremental = flags.getOrDefault(KotlinCompilerFlags.incremental)
        if (incremental && cacheFolder == null) {
            logger.warn(loggerMarker, "Disabling incremental compilation: null cache folder")
            incremental = false
        }

        val exitCode: ExitCode
        if (incremental) {
            // Based on org.jetbrains.kotlin.incremental.IncrementalJvmCompilerRunner.makeIncrementally
            val cachedOutput = cacheFolder!!.resolve("output")
            Files.createDirectories(cachedOutput)
            args.destination = cachedOutput.absolutePath
            args.javaSourceRoots = run {
                val rootSet = HashSet<String>()
                sources.mapNotNullTo(rootSet) { it.root?.toString() }
                rootSet.toTypedArray()
            }

            val kotlincCache = cacheFolder.resolve("kotlinc-cache")
            Files.createDirectories(kotlincCache)
            val kotlincCachesDir = kotlincCache.toFile()

            val buildHistoryFile = File(kotlincCachesDir, "build-history.bin")

            exitCode = withIC {
                val compiler = IncrementalJvmCompilerRunner(
                        kotlincCachesDir,
                        object : ICReporter {
                            override fun report(message: () -> String) {
                                if (logger.isDebugEnabled(loggerMarker)) {
                                    logger.debug(loggerMarker, "IC: {}", message())
                                }
                            }

                            override fun reportCompileIteration(incremental: Boolean, sourceFiles: Collection<File>, exitCode: ExitCode) {}

                            override fun reportMarkDirty(affectedFiles: Iterable<File>, reason: String) {}

                            override fun reportMarkDirtyClass(affectedFiles: Iterable<File>, classFqName: String) {}

                            override fun reportMarkDirtyMember(affectedFiles: Iterable<File>, scope: String, name: String) {}

                            override fun reportVerbose(message: () -> String) {
                                if (logger.isTraceEnabled(loggerMarker)) {
                                    logger.trace(loggerMarker, "IC: {}", message())
                                }
                            }
                        },
                        // Use precise setting in case of non-Gradle build
                        usePreciseJavaTracking = true,
                        // Directories that we want deleted on full rebuild
                        // https://github.com/JetBrains/kotlin/commit/30d0cc3a34fdfc15debcd5c9eb4b009d7c1df624
                        // https://github.com/JetBrains/kotlin/commit/9176f9b254cc15a2f81377a61832e921019fc624#diff-c4c0c033fbc8b4c2cd79af6e68b1894a
                        outputFiles = emptyList(),
                        buildHistoryFile = buildHistoryFile,
                        modulesApiHistory = EmptyModulesApiHistory,
                        kotlinSourceFilesExtensions = DEFAULT_KOTLIN_SOURCE_FILES_EXTENSIONS)
                compiler.compile(sources.map { it.file.toFile() }, args, messageCollector, providedChangedFiles = null)
            }

            if (exitCode == ExitCode.OK) {
                // Copy kotlin output to real output,
                cachedOutput.copyRecursively(destination)
            }
        } else {
            args.destination = destination.absolutePath
            args.freeArgs = sources.map { it.file.absolutePath }
            exitCode = K2JVMCompiler().exec(messageCollector, Services.EMPTY, args)
        }

        // Done
        return when (exitCode) {
            ExitCode.OK -> OK
            ExitCode.COMPILATION_ERROR -> COMPILATION_ERROR
            ExitCode.INTERNAL_ERROR -> INTERNAL_ERROR
            ExitCode.SCRIPT_EXECUTION_ERROR -> INTERNAL_ERROR
        }
    }

    private fun createLoggingMessageCollector(log: Logger, marker: Marker?): MessageCollector {
        return object : MessageCollector {

            var hasErrors = false
            override fun clear() {
                // Their implementation doesn't clear errors, so, yeah
            }

            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
                hasErrors = hasErrors || severity.isError
                val loc = if (location == null)
                    null
                else
                    MessageLocation(location.path, location.line, location.column, location.lineContent)
                log.render(marker, severity.name, message, loc)
            }

            override fun hasErrors(): Boolean = hasErrors

        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger("KotlinCompilerBootstrap")
    }
}