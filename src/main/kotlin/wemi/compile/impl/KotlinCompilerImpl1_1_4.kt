package wemi.compile.impl

import com.intellij.core.CoreFileTypeRegistry
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.Disposer
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.*
import org.jetbrains.kotlin.cli.jvm.BundledCompilerPlugins
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmModulePathRoot
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.compiler.plugin.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.javac.JavacWrapper
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents
import org.jetbrains.kotlin.progress.CompilationCanceledException
import org.jetbrains.kotlin.progress.CompilationCanceledStatus
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.util.PerformanceCounter
import org.jetbrains.kotlin.utils.PathUtil
import org.slf4j.Logger
import org.slf4j.Marker
import wemi.boot.WemiBuildFileExtensions
import wemi.compile.*
import wemi.compile.KotlinCompiler.CompileExitStatus.*
import wemi.util.LocatedFile
import wemi.util.hasExtension
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.*


@Suppress("unused")
/**
 * Kotlin compiler interface implementation, DO NOT TOUCH FROM OTHER CLASSES THAN [KotlinCompiler]!!!
 */
internal class KotlinCompilerImpl1_1_4 : KotlinCompiler {

    private fun KotlinCompilerFlags.FeatureState.internal(): LanguageFeature.State {
        return when (this) {
            KotlinCompilerFlags.FeatureState.Enabled -> LanguageFeature.State.ENABLED
            KotlinCompilerFlags.FeatureState.EnabledWithWarning -> LanguageFeature.State.ENABLED_WITH_WARNING
            KotlinCompilerFlags.FeatureState.EnabledWithError -> LanguageFeature.State.ENABLED_WITH_ERROR
            KotlinCompilerFlags.FeatureState.Disabled -> LanguageFeature.State.DISABLED
        }
    }

    private fun KotlinJVMCompilerFlags.BytecodeTarget.internal(): JvmTarget {
        return when (this) {
            KotlinJVMCompilerFlags.BytecodeTarget.JAVA_1_6 -> JvmTarget.JVM_1_6
            KotlinJVMCompilerFlags.BytecodeTarget.JAVA_1_8 -> JvmTarget.JVM_1_8
        }
    }

    private fun parseVersion(value: String?, versionOf: String, messageCollector: MessageCollector): LanguageVersion? {
        val version = LanguageVersion.fromVersionString(value ?: return null)
        if (version != null) {
            return version
        }

        val versionStrings = LanguageVersion.values().map { it.description }
        val message = "Unknown " + versionOf + " version: " + value + "\n" +
                "Supported " + versionOf + " versions: " + versionStrings.joinToString(", ")
        messageCollector.report(ERROR, message, null)
        return null
    }

    private fun setupLanguageVersionSettings(configuration: CompilerConfiguration, flags: CompilerFlags, messageCollector: MessageCollector) {
        var languageVersion = parseVersion(flags.useOrNull(KotlinCompilerFlags.languageVersion), "language", messageCollector)
        var apiVersion = parseVersion(flags.useOrNull(KotlinCompilerFlags.apiVersion), "API", messageCollector)

        if (languageVersion == null) {
            // If only "-api-version" is specified, language version is assumed to be the latest stable
            languageVersion = LanguageVersion.LATEST_STABLE
        }

        if (apiVersion == null) {
            // If only "-language-version" is specified, API version is assumed to be equal to the language version
            // (API version cannot be greater than the language version)
            apiVersion = languageVersion
        } else {
            configuration.put(CLIConfigurationKeys.IS_API_VERSION_EXPLICIT, true)
        }

        if (apiVersion > languageVersion) {
            messageCollector.report(
                    ERROR,
                    "-api-version (" + apiVersion.versionString + ") cannot be greater than " +
                            "-language-version (" + languageVersion.versionString + ")", null
            )
        }

        if (!languageVersion.isStable) {
            messageCollector.report(
                    STRONG_WARNING,
                    "Language version " + languageVersion.versionString + " is experimental, there are " +
                            "no backwards compatibility guarantees for new language and library features", null
            )
        }

        val extraLanguageFeatures = HashMap<LanguageFeature, LanguageFeature.State>(0)
        flags.use(KotlinCompilerFlags.multiPlatform) {
            extraLanguageFeatures.put(LanguageFeature.MultiPlatformProjects, it.internal())
        }

        flags.use(KotlinCompilerFlags.coroutinesState) {
            extraLanguageFeatures.put(LanguageFeature.Coroutines, it.internal())
        }

        val configureAnalysisFlags = HashMap<AnalysisFlag<*>, Any?>()
        configureAnalysisFlags.put(AnalysisFlag.skipMetadataVersionCheck, flags.useDefault(KotlinCompilerFlags.skipMetadataVersionCheck, false))
        configureAnalysisFlags.put(AnalysisFlag.multiPlatformDoNotCheckImpl, flags.useDefault(KotlinCompilerFlags.multiPlatformDoNotCheckImpl, false))
        configureAnalysisFlags.put(AnalysisFlag.loadJsr305Annotations, when (flags.useDefault(KotlinJVMCompilerFlags.jsr305GlobalReportLevel, KotlinJVMCompilerFlags.Jsr305GlobalReportLevel.Ignore)) {
            KotlinJVMCompilerFlags.Jsr305GlobalReportLevel.Ignore -> Jsr305State.IGNORE
            KotlinJVMCompilerFlags.Jsr305GlobalReportLevel.Warn -> Jsr305State.WARN
            KotlinJVMCompilerFlags.Jsr305GlobalReportLevel.Enable -> Jsr305State.ENABLE
        })

        configuration.languageVersionSettings = LanguageVersionSettingsImpl(
                languageVersion,
                ApiVersion.createByLanguageVersion(apiVersion),
                configureAnalysisFlags,
                extraLanguageFeatures
        )
    }

    private fun setupJdkClasspathRoots(configuration: CompilerConfiguration, flags: CompilerFlags, messageCollector: MessageCollector): KotlinCompiler.CompileExitStatus {
        try {
            val noJdk = flags.useDefault(KotlinJVMCompilerFlags.noJdk, false)
            val jdkHomeArgs = flags.useOrNull(KotlinJVMCompilerFlags.jdkHome)

            if (noJdk) {
                if (jdkHomeArgs != null) {
                    messageCollector.report(STRONG_WARNING, "The '-jdk-home' option is ignored because '-no-jdk' is specified")
                }
                return OK
            }

            val (jdkHome, classesRoots) = if (jdkHomeArgs != null) {
                val jdkHome = File(jdkHomeArgs)
                if (!jdkHome.exists()) {
                    messageCollector.report(ERROR, "JDK home directory does not exist: $jdkHome")
                    return COMPILATION_ERROR
                }
                messageCollector.report(LOGGING, "Using JDK home directory $jdkHome")
                jdkHome to PathUtil.getJdkClassesRoots(jdkHome)
            } else {
                File(System.getProperty("java.home")) to PathUtil.getJdkClassesRootsFromCurrentJre()
            }

            configuration.put(JVMConfigurationKeys.JDK_HOME, jdkHome)

            if (!CoreJrtFileSystem.isModularJdk(jdkHome)) {
                configuration.addJvmClasspathRoots(classesRoots)
                if (classesRoots.isEmpty()) {
                    messageCollector.report(ERROR, "No class roots are found in the JDK path: $jdkHome")
                    return COMPILATION_ERROR
                }
            }
            return OK
        } catch (t: Throwable) {
            MessageCollectorUtil.reportException(messageCollector, t)
            return INTERNAL_ERROR
        }
    }

    private fun setupCommonConfiguration(configuration: CompilerConfiguration, flags: CompilerFlags, messageCollector: MessageCollector): KotlinCompiler.CompileExitStatus {
        flags.use(KotlinCompilerFlags.noInline) {
            configuration.put(CommonConfigurationKeys.DISABLE_INLINE, it)
        }
        flags.use(KotlinCompilerFlags.intellijPluginRoot) {
            configuration.put(CLIConfigurationKeys.INTELLIJ_PLUGIN_ROOT, it)
        }
        flags.use(KotlinCompilerFlags.reportOutputFiles) {
            configuration.put(CommonConfigurationKeys.REPORT_OUTPUT_FILES, it)
        }

        try {
            loadPlugins(configuration, flags)
        } catch (e: PluginCliOptionProcessingException) {
            val message = e.message + "\n\n" + cliPluginUsageString(e.pluginId, e.options)
            messageCollector.report(ERROR, message)
            return INTERNAL_ERROR
        } catch (e: CliOptionProcessingException) {
            messageCollector.report(ERROR, e.message!!)
            return INTERNAL_ERROR
        } catch (t: Throwable) {
            MessageCollectorUtil.reportException(messageCollector, t)
            return INTERNAL_ERROR
        }

        configuration.put(CommonConfigurationKeys.MODULE_NAME, flags.useDefault(KotlinJVMCompilerFlags.moduleName, JvmAbi.DEFAULT_MODULE_NAME))

        configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, flags.useDefault(KotlinCompilerFlags.allowKotlinPackage, false))

        return OK
    }

    private fun setupJVMConfiguration(configuration: CompilerConfiguration, flags: CompilerFlags, services: Services, messageCollector: MessageCollector) {
        if (IncrementalCompilation.isEnabled()) {
            val components = services.get(IncrementalCompilationComponents::class.java)
            if (components != null) {
                configuration.put(JVMConfigurationKeys.INCREMENTAL_COMPILATION_COMPONENTS, components)
            }
        }

        flags.use(KotlinJVMCompilerFlags.additionalJavaModules) {
            configuration.addAll(JVMConfigurationKeys.ADDITIONAL_JAVA_MODULES, it.toList())
        }

        flags.use(KotlinJVMCompilerFlags.includeRuntime) {
            configuration.put(JVMConfigurationKeys.INCLUDE_RUNTIME, it)
        }
        flags.use(KotlinJVMCompilerFlags.friendPaths) {
            configuration.put(JVMConfigurationKeys.FRIEND_PATHS, it.toList())
        }

        flags.use(KotlinJVMCompilerFlags.jvmTarget) {
            configuration.put(JVMConfigurationKeys.JVM_TARGET, it.internal())
        }

        configuration.put(JVMConfigurationKeys.PARAMETERS_METADATA, flags.useDefault(KotlinJVMCompilerFlags.javaParameters, false))

        configuration.put(JVMConfigurationKeys.DISABLE_CALL_ASSERTIONS, flags.useDefault(KotlinJVMCompilerFlags.noCallAssertions, false))
        configuration.put(JVMConfigurationKeys.DISABLE_PARAM_ASSERTIONS, flags.useDefault(KotlinJVMCompilerFlags.noParamAssertions, false))
        configuration.put(JVMConfigurationKeys.DISABLE_OPTIMIZATION, flags.useDefault(KotlinJVMCompilerFlags.noOptimize, false))
        configuration.put(JVMConfigurationKeys.INHERIT_MULTIFILE_PARTS, flags.useDefault(KotlinJVMCompilerFlags.inheritMultifileParts, false))
        configuration.put(JVMConfigurationKeys.SKIP_RUNTIME_VERSION_CHECK, flags.useDefault(KotlinJVMCompilerFlags.skipRuntimeVersionCheck, false))
        val useOldClassFilesReading = flags.useDefault(KotlinJVMCompilerFlags.useOldClassFilesReading, false)
        configuration.put(JVMConfigurationKeys.USE_FAST_CLASS_FILES_READING, !useOldClassFilesReading)
        if (useOldClassFilesReading) {
            messageCollector.report(INFO, "Using the old java class files reading implementation")
        }

        configuration.put(JVMConfigurationKeys.USE_SINGLE_MODULE, flags.useDefault(KotlinJVMCompilerFlags.singleModule, false))
        configuration.put(JVMConfigurationKeys.ADD_BUILT_INS_FROM_COMPILER_TO_DEPENDENCIES, flags.useDefault(KotlinJVMCompilerFlags.addCompilerBuiltIns, false))
        configuration.put(JVMConfigurationKeys.CREATE_BUILT_INS_FROM_MODULE_DEPENDENCIES, flags.useDefault(KotlinJVMCompilerFlags.loadBuiltInsFromDependencies, false))

        flags.use(KotlinJVMCompilerFlags.declarationsOutputPath) {
            configuration.put(JVMConfigurationKeys.DECLARATIONS_JSON_PATH, it)
        }
    }

    private fun setupReportPerf(configuration: CompilerConfiguration, flags: CompilerFlags): Boolean {
        val reportPerf = flags.useDefault(KotlinJVMCompilerFlags.reportPerf, false)
        PerformanceCounter.setTimeCounterEnabled(reportPerf)
        configuration.put(CLIConfigurationKeys.REPORT_PERF, reportPerf)
        return reportPerf
    }

    /**
     * @param sources java and kotlin source files (or source roots)
     */
    private fun setupSources(configuration: CompilerConfiguration, flags: CompilerFlags, sources: List<LocatedFile>, messageCollector: MessageCollector) {
        for (source in sources) {
            val file = source.file
            when {
                file.isDirectory -> {
                    configuration.addKotlinSourceRoot(file.absolutePath)
                    configuration.addJavaSourceRoot(file)
                }
                file.hasExtension(KotlinSourceFileExtensions) -> {
                    configuration.addKotlinSourceRoot(file.absolutePath)
                }
                file.hasExtension(JavaSourceFileExtensions) -> {
                    configuration.addJavaSourceRoot(file, source.packageName)
                }
                flags.useDefault(KotlinJVMCompilerFlags.compilingWemiBuildFiles, false)
                        && file.hasExtension(WemiBuildFileExtensions) -> {
                    configuration.addKotlinSourceRoot(file.absolutePath)
                }
                else -> {
                    messageCollector.report(WARNING, "Unrecognized source file, ignoring: "+file.absolutePath)
                }
            }
        }
    }

    private fun setupClasspath(configuration: CompilerConfiguration, flags: CompilerFlags, classpath: List<File>) {
        for (classpathItem in classpath) {
            configuration.add(JVMConfigurationKeys.CONTENT_ROOTS, JvmClasspathRoot(classpathItem))
        }

        for (modularRoot in flags.useOrNull(KotlinJVMCompilerFlags.javaModulePath)?.split(File.pathSeparatorChar).orEmpty()) {
            configuration.add(JVMConfigurationKeys.CONTENT_ROOTS, JvmModulePathRoot(File(modularRoot)))
        }
    }

    private fun setupDestination(configuration: CompilerConfiguration, destination: File) {
        if (destination.name.endsWith(".jar", ignoreCase = true)) {
            configuration.put(JVMConfigurationKeys.OUTPUT_JAR, destination)
        } else {
            configuration.put(JVMConfigurationKeys.OUTPUT_DIRECTORY, destination)
        }
    }

    override fun compile(sources: List<LocatedFile>,
                         classpath: List<File>,
                         destination: File,
                         flags: CompilerFlags,
                         logger: Logger,
                         loggerMarker: Marker?): KotlinCompiler.CompileExitStatus {

        val messageCollector = GroupingMessageCollector(createLoggingMessageCollector(logger, loggerMarker))

        val services = Services.EMPTY

        try {
            val exitStatus: KotlinCompiler.CompileExitStatus
            val canceledStatus = services.get(CompilationCanceledStatus::class.java)
            ProgressIndicatorAndCompilationCanceledStatus.setCompilationCanceledStatus(canceledStatus)

            val rootDisposable = Disposer.newDisposable()
            try {
                setIdeaIoUseFallback() // No idea what this does, remove?

                val configuration = CompilerConfiguration()
                configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)

                val reportPerf = setupReportPerf(configuration, flags)
                setupJdkClasspathRoots(configuration, flags, messageCollector).let { if (it != OK) return it }
                setupLanguageVersionSettings(configuration, flags, messageCollector)
                setupCommonConfiguration(configuration, flags, messageCollector).let { if (it != OK) return it }
                setupJVMConfiguration(configuration, flags, services, messageCollector)
                setupSources(configuration, flags, sources, messageCollector)
                setupClasspath(configuration, flags, classpath)
                setupDestination(configuration, destination)

                if (flags.useDefault(KotlinJVMCompilerFlags.compilingWemiBuildFiles, false)) {
                    // Hack to recognize .wemi files as kotlin files
                    // This has to be done as a "plugin" because that seems to be the only way to get hold of
                    // the file type registry before it is too late. By masquerading as a plugin, we get to
                    // execute arbitrary code when the FileTypeRegistry global is properly initialized,
                    // so we can register our type.
                    configuration.add(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, object:ComponentRegistrar {
                        override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
                            val registry = FileTypeRegistry.getInstance() as CoreFileTypeRegistry
                            // This registers the KotlinFileType multiple times, but the official code does that as well
                            registry.registerFileType(KotlinFileType.INSTANCE, WemiBuildFileExtensions.joinToString(";"))
                        }
                    })
                }

                val code = doCompile(configuration, rootDisposable, flags, messageCollector)
                exitStatus = if (messageCollector.hasErrors()) COMPILATION_ERROR else code

                if (reportPerf) {
                    K2JVMCompiler.reportGCTime(configuration)
                    K2JVMCompiler.reportCompilationTime(configuration)
                    PerformanceCounter.report { s -> K2JVMCompiler.reportPerf(configuration, s) }
                }
            } catch (e: CompilationCanceledException) {
                messageCollector.report(INFO, "Compilation was canceled", null)
                return CANCELLED
            } catch (e: RuntimeException) {
                val cause = e.cause
                if (cause is CompilationCanceledException) {
                    messageCollector.report(INFO, "Compilation was canceled", null)
                    return CANCELLED
                } else {
                    throw e
                }
            } finally {
                Disposer.dispose(rootDisposable)
            }

            return exitStatus
        } catch (t: Throwable) {
            MessageCollectorUtil.reportException(messageCollector, t)
            return INTERNAL_ERROR
        } finally {
            messageCollector.flush()
        }
    }

    private fun doCompile(configuration: CompilerConfiguration, rootDisposable: Disposable, flags: CompilerFlags, messageCollector: MessageCollector): KotlinCompiler.CompileExitStatus {
        try {
            val environment = KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

            if (messageCollector.hasErrors()) {
                return COMPILATION_ERROR
            }

            if (flags.useDefault(KotlinJVMCompilerFlags.useJavac, false)) {
                environment.configuration.put(JVMConfigurationKeys.USE_JAVAC, true)
                if (!environment.registerJavac(arguments = flags.useOrNull(KotlinJVMCompilerFlags.javacArguments))) {
                    return COMPILATION_ERROR
                }
            }

            if (environment.getSourceFiles().isEmpty()) {
                messageCollector.report(ERROR, "No source files")
                return COMPILATION_ERROR
            }

            KotlinToJVMBytecodeCompiler.compileBunchOfSources(environment)

            if (flags.useDefault(KotlinJVMCompilerFlags.useJavac, false)) {
                val javac = JavacWrapper.getInstance(environment.project)
                if (!javac.compile()) {
                    return COMPILATION_ERROR
                }
            }

            return OK
        } catch (e: CompilationException) {
            messageCollector.report(EXCEPTION, OutputMessageUtil.renderException(e),
                    MessageUtil.psiElementToMessageLocation(e.element))
            return INTERNAL_ERROR
        }
    }

    private fun loadPlugins(configuration: CompilerConfiguration, flags: CompilerFlags) {
        val classLoader = PluginURLClassLoader(
                flags.useOrNull(KotlinCompilerFlags.pluginClasspaths)
                        ?.map { File(it).toURI().toURL() }
                        ?.toTypedArray()
                        ?: arrayOf(),
                this::class.java.classLoader
        )

        val componentRegistrars = ServiceLoader.load(ComponentRegistrar::class.java, classLoader).toMutableList()
        componentRegistrars.addAll(BundledCompilerPlugins.componentRegistrars)
        configuration.addAll(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, componentRegistrars)

        val optionValuesByPlugin = flags.useOrNull(KotlinCompilerFlags.pluginOptions)?.map { parsePluginOption(it) }?.groupBy {
            if (it == null) throw CliOptionProcessingException("Wrong plugin option format: $it, should be ${CommonCompilerArguments.PLUGIN_OPTION_FORMAT}")
            it.pluginId
        } ?: mapOf()
        val commandLineProcessors = ServiceLoader.load(CommandLineProcessor::class.java, classLoader).toMutableList()
        commandLineProcessors.addAll(BundledCompilerPlugins.commandLineProcessors)
        for (processor in commandLineProcessors) {
            val declaredOptions = processor.pluginOptions.associateBy { it.name }
            val optionsToValues = MultiMap<CliOption, CliOptionValue>()

            for (optionValue in optionValuesByPlugin[processor.pluginId].orEmpty()) {
                val option = declaredOptions[optionValue!!.optionName]
                        ?: throw CliOptionProcessingException("Unsupported plugin option: $optionValue")
                optionsToValues.putValue(option, optionValue)
            }

            for (option in processor.pluginOptions) {
                val values = optionsToValues[option]
                if (option.required && values.isEmpty()) {
                    throw PluginCliOptionProcessingException(
                            processor.pluginId,
                            processor.pluginOptions,
                            "Required plugin option not present: ${processor.pluginId}:${option.name}")
                }
                if (!option.allowMultipleOccurrences && values.size > 1) {
                    throw PluginCliOptionProcessingException(
                            processor.pluginId,
                            processor.pluginOptions,
                            "Multiple values are not allowed for plugin option ${processor.pluginId}:${option.name}")
                }

                for (value in values) {
                    processor.processOption(option, value.value, configuration)
                }
            }
        }
    }

    private class PluginURLClassLoader(urls: Array<URL>, parent: ClassLoader) : ClassLoader(Thread.currentThread().contextClassLoader) {
        private val childClassLoader: SelfThenParentURLClassLoader = SelfThenParentURLClassLoader(urls, parent)

        @Synchronized
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            return try {
                childClassLoader.findClass(name)
            } catch (e: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }

        override fun getResources(name: String): Enumeration<URL> = childClassLoader.getResources(name)

        private class SelfThenParentURLClassLoader(urls: Array<URL>, private val onFail: ClassLoader) : URLClassLoader(urls, null) {

            public override fun findClass(name: String): Class<*> {
                val loaded = findLoadedClass(name)
                if (loaded != null) {
                    return loaded
                }

                return try {
                    super.findClass(name)
                } catch (e: ClassNotFoundException) {
                    onFail.loadClass(name)
                }
            }
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
}