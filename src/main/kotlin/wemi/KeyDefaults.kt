@file:Suppress("MemberVisibilityCanBePrivate")

package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.boot.WemiBundledLibrariesExclude
import wemi.boot.WemiVersion
import wemi.collections.WMutableList
import wemi.compile.JavaCompilerFlags
import wemi.compile.JavaSourceFileExtensions
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.compile.KotlinSourceFileExtensions
import wemi.compile.internal.createJavaObjectFileDiagnosticLogger
import wemi.dependency.DEFAULT_OPTIONAL
import wemi.dependency.DEFAULT_SCOPE
import wemi.dependency.DEFAULT_TYPE
import wemi.dependency.Dependency
import wemi.dependency.DependencyId
import wemi.dependency.JCenter
import wemi.dependency.MavenCentral
import wemi.dependency.NoClassifier
import wemi.dependency.ProjectDependency
import wemi.dependency.Repository
import wemi.dependency.ResolvedDependencies
import wemi.dependency.ScopeAggregate
import wemi.dependency.ScopeCompile
import wemi.dependency.internal.publish
import wemi.dependency.joinClassifiers
import wemi.dependency.resolveDependencies
import wemi.dependency.resolveDependencyArtifacts
import wemi.documentation.DokkaInterface
import wemi.documentation.DokkaOptions
import wemi.publish.InfoNode
import wemi.run.ExitCode
import wemi.run.prepareJavaProcess
import wemi.run.prepareJavaProcessCommand
import wemi.run.runForegroundProcess
import wemi.test.TEST_LAUNCHER_MAIN_CLASS
import wemi.test.TestParameters
import wemi.test.TestReport
import wemi.test.handleProcessForTesting
import wemi.test.withPrefixContainer
import wemi.util.CycleChecker
import wemi.util.EnclaveClassLoader
import wemi.util.LineReadingWriter
import wemi.util.LocatedPath
import wemi.util.Magic
import wemi.util.Partial
import wemi.util.ScopedLocatedPath
import wemi.util.absolutePath
import wemi.util.appendCentered
import wemi.util.appendTimes
import wemi.util.constructLocatedFiles
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.exists
import wemi.util.format
import wemi.util.isDirectory
import wemi.util.javadocUrl
import wemi.util.jdkToolsJar
import wemi.util.name
import wemi.util.parseJavaVersion
import wemi.util.pathExtension
import wemi.util.pathWithoutExtension
import wemi.util.prettyPrint
import wemi.util.scoped
import wemi.util.toSafeFileName
import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.*
import javax.tools.DocumentationTool
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet

/**
 * Contains default values bound to keys.
 *
 * This includes implementations of most tasks.
 */
object KeyDefaults {

    /** Create value for [Keys.libraryDependencyMapper] that appends given classifier to sources. */
    fun classifierAppendingLibraryDependencyProjectMapper(appendClassifier: String): (Dependency) -> Dependency = { dep ->
        val classifier = joinClassifiers(dep.dependencyId.classifier, appendClassifier)
        dep.copy(dep.dependencyId.copy(classifier = classifier))
    }

    /** Create BoundKeyValueModifier which takes a list of classpath entries (.jars or folders),
     * appends given classifier to each one and filters out only files that exist.
     * For example, `library.jar` could be translated to `library-sources.jar` with `"sources"` classifier. */
    fun classifierAppendingClasspathModifier(appendClassifier:String):ValueModifier<List<LocatedPath>> = { originalList ->
        originalList.mapNotNull { originalLocatedPath ->
            val originalPath = originalLocatedPath.classpathEntry

            val originalName = originalPath.name
            val extension = originalName.pathExtension()
            val newName = if (originalPath.isDirectory() || extension.isEmpty()) {
                "$originalName-$appendClassifier"
            } else {
                "${originalName.pathWithoutExtension()}-$appendClassifier.$extension"
            }
            val newFile = originalPath.resolveSibling(newName)
            if (newFile.exists()) {
                // There indeed is a valid path here
                if (originalLocatedPath.root == null) {
                    // Original LocatedPath was simple
                    LocatedPath(originalPath)
                } else {
                    val newResolvedFile = newFile.resolve(originalLocatedPath.root.relativize(originalLocatedPath.file))
                    if (newResolvedFile.exists()) {
                        // We can fully reconstruct the tree
                        LocatedPath(newFile, newResolvedFile)
                    } else {
                        // It is not possible to reconstruct the tree, but we can keep the root
                        LocatedPath(newFile)
                    }
                }
            } else {
                // Translation is possible, but the file does not exist
                null
            }
        }
    }

    val MakeAllRepositoriesAuthoritative:ValueModifier<Set<Repository>> = { repositories ->
        repositories.mapTo(HashSet()) { if (!it.authoritative) it.copy(authoritative = true) else it }
    }

    val ResolvedLibraryDependencies: Value<Partial<ResolvedDependencies>> =  {
        val repositories = Keys.repositories.get()
        val libraryDependencies = Keys.libraryDependencies.get()
        val libraryDependencyProjectMapper = Keys.libraryDependencyMapper.get()
        resolveDependencies(libraryDependencies, repositories, libraryDependencyProjectMapper, progressListener)
    }

    private val inProjectDependencies_CircularDependencyProtection = CycleChecker<Scope>()
    fun EvalScope.inProjectDependencies(operation: EvalScope.(dep: ProjectDependency) -> Unit) {
        inProjectDependencies_CircularDependencyProtection.block(this.scope, failure = { loop ->
            throw WemiException("Cyclic dependencies in projectDependencies are not allowed (${loop.joinToString(" -> ")})", showStacktrace = false)
        }, action = {
            val projectDependencies = Keys.projectDependencies.get()

            for (projectDependency in projectDependencies) {
                // Enter a different scope and perform the operation
                using(projectDependency) {
                    operation(projectDependency)
                }
            }
        })
    }

    private val ExternalClasspath_LOG = LoggerFactory.getLogger("ExternalClasspath")
    val ExternalClasspath: Value<List<ScopedLocatedPath>> = {
        val result = LinkedHashSet<ScopedLocatedPath>()

        val resolved = Keys.resolvedLibraryDependencies.get()
        if (!resolved.complete) {
            val message = StringBuilder()
            message.append("Failed to resolve all artifacts")
                    .format() // Required, because printing it with pre-set color would bleed into the pretty-printed values
                    .append('\n')
                    .append(resolved.value.prettyPrint())
            throw WemiException(message.toString(), showStacktrace = false)
        }

        for ((_, resolvedDependency) in resolved.value) {
            result.add(LocatedPath(resolvedDependency.artifact?.path ?: continue).scoped(resolvedDependency.scope))
        }

        inProjectDependencies { projectDependency ->
            ExternalClasspath_LOG.debug("Resolving project dependency on {}", this)
            result.addAll(Keys.externalClasspath.get())
            for (path in Keys.internalClasspath.get()) {
                result.add(path.scoped(projectDependency.scope))
            }
        }

        val unmanaged = Keys.unmanagedDependencies.get()
        for (path in unmanaged) {
            result.add(path.scoped(ScopeCompile))
        }

        WMutableList(result)
    }

    /** Modified [ExternalClasspath] for [Configurations.ideImport] */
    val ExternalClasspathForIdeImport:Value<List<ScopedLocatedPath>> = {
        val result = LinkedHashSet<ScopedLocatedPath>()

        val resolved = Keys.resolvedLibraryDependencies.get()
        if (!resolved.complete) {
            val message = StringBuilder()
            message.append("Failed to resolve all artifacts")
                    .format() // Required, because printing it with pre-set color would bleed into the pretty-printed values
                    .append('\n')
                    .append(resolved.value.prettyPrint())
            // No throw on incomplete resolution
            ExternalClasspath_LOG.warn("{}", message)
        }

        for ((_, resolvedDependency) in resolved.value) {
            result.add(LocatedPath(resolvedDependency.artifact?.path ?: continue).scoped(resolvedDependency.scope))
        }

        // No project dependencies

        val unmanaged = Keys.unmanagedDependencies.get()
        for (path in unmanaged) {
            result.add(path.scoped(ScopeCompile))
        }

        WMutableList(result)
    }

    fun internalClasspath(compile:Boolean): Value<List<LocatedPath>> = {
        val classpath = LinkedHashSet<LocatedPath>()
        if (compile) {
            constructLocatedFiles(Keys.compile.get(), classpath)
        }

        classpath.addAll(Keys.generatedClasspath.get())
        classpath.addAll(Keys.resources.getLocatedPaths())

        WMutableList(classpath)
    }

    /** Modified [internalClasspath] for IDE import. Does not include [Keys.compile] nor [Keys.resources],
     * as those are handled separately. */
    val InternalClasspathForIdeImport: Value<List<LocatedPath>> = {
        Keys.generatedClasspath.get()
    }

    fun outputClassesDirectory(tag: String): Value<Path> = {
        // Using scopeProject() instead of Keys.projectName, because it has to be unique
        // Prefix - signifies that it should be deleted on clean command
        Keys.cacheDirectory.get() / "-$tag-${scope.project.name.toSafeFileName('_')}"
    }

    private val KotlincLOG = LoggerFactory.getLogger("Kotlinc")
    private val JavacLOG = LoggerFactory.getLogger("Javac")
    private val JavaDiagnosticListener = createJavaObjectFileDiagnosticLogger(JavacLOG)

    val CompileJava: Value<Path> = {
        val output = Keys.outputClassesDirectory.get()
        output.ensureEmptyDirectory()

        val javaSources = Keys.sources.getPaths(*JavaSourceFileExtensions)

        val externalClasspath = LinkedHashSet<String>()
        for (path in Keys.externalClasspath.getLocatedPathsForScope(Keys.scopesCompile.get())) {
                externalClasspath.add(path.classpathEntry.absolutePath)
        }
        for (path in Keys.generatedClasspath.get()) {
            externalClasspath.add(path.classpathEntry.absolutePath)
        }

        // Compile Java
        if (javaSources.isNotEmpty()) {
            val compiler = Keys.javaCompiler.get()
            val fileManager = compiler.getStandardFileManager(JavaDiagnosticListener, Locale.getDefault(), StandardCharsets.UTF_8) ?: throw WemiException("No standardFileManager")
            val writerSb = StringBuilder()
            val writer = StringBuilderWriter(writerSb)
            val compilerFlags = Keys.compilerOptions.get()

            val sourcesOut = Keys.outputSourcesDirectory.get()
            sourcesOut.ensureEmptyDirectory()
            val headersOut = Keys.outputHeadersDirectory.get()
            headersOut.ensureEmptyDirectory()

            val pathSeparator = System.getProperty("path.separator", ":")
            val compilerOptions = ArrayList<String>()
            compilerFlags.use(JavaCompilerFlags.customFlags) {
                compilerOptions.addAll(it)
            }
            compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                if (it.isNotEmpty()) {
                    compilerOptions.add("-source")
                    compilerOptions.add(it)
                }
            }
            compilerFlags.use(JavaCompilerFlags.targetVersion) {
                if (it.isNotEmpty()) {
                    compilerOptions.add("-target")
                    compilerOptions.add(it)
                }
            }
            compilerFlags.use(JavaCompilerFlags.encoding) {
                if (it.isNotEmpty()) {
                    compilerOptions.add("-encoding")
                    compilerOptions.add(it)
                }
            }
            compilerOptions.add("-classpath")
            val classpathString = externalClasspath.joinToString(pathSeparator)
            compilerOptions.add(classpathString)
            compilerOptions.add("-d")
            compilerOptions.add(output.absolutePath)
            compilerOptions.add("-s")
            compilerOptions.add(sourcesOut.absolutePath)
            compilerOptions.add("-h")
            compilerOptions.add(headersOut.absolutePath)

            val javaFiles = fileManager.getJavaFileObjectsFromFiles(javaSources.map { it.toFile() })

            val success = compiler.getTask(
                    writer,
                    fileManager,
                    JavaDiagnosticListener,
                    compilerOptions,
                    null,
                    javaFiles
            ).call()

            if (!writerSb.isBlank()) {
                val format = if (writerSb.contains('\n')) "\n{}" else "{}"
                if (success) {
                    JavacLOG.info(format, writerSb)
                } else {
                    JavacLOG.warn(format, writerSb)
                }
            }

            if (!success) {
                throw WemiException.CompilationException("Java compilation failed")
            }
        }

        output
    }

    val CompileJavaKotlin: Value<Path> = {
        val output = Keys.outputClassesDirectory.get()
        output.ensureEmptyDirectory()

        val compilerFlags = Keys.compilerOptions.get()
        val javaSources = Keys.sources.getLocatedPaths(*JavaSourceFileExtensions)
        val kotlinSources = Keys.sources.getLocatedPaths(*KotlinSourceFileExtensions)

        val externalClasspath = LinkedHashSet<Path>()
        for (path in Keys.externalClasspath.getLocatedPathsForScope(Keys.scopesCompile.get())) {
            externalClasspath.add(path.classpathEntry)
        }
        for (path in Keys.generatedClasspath.get()) {
            externalClasspath.add(path.classpathEntry)
        }

        // Compile Kotlin
        if (kotlinSources.isNotEmpty()) {
            val compiler = Keys.kotlinCompiler.get()

            val cacheFolder = output.resolveSibling(output.name + "-kotlin-cache")
            Files.createDirectories(cacheFolder)

            val compileResult = compiler.compileJVM(javaSources + kotlinSources, externalClasspath, output, cacheFolder, compilerFlags, KotlincLOG, null)
            when (compileResult) {
                KotlinCompiler.CompileExitStatus.OK -> {}
                KotlinCompiler.CompileExitStatus.CANCELLED -> throw WemiException.CompilationException("Kotlin compilation has been cancelled")
                KotlinCompiler.CompileExitStatus.COMPILATION_ERROR -> throw WemiException.CompilationException("Kotlin compilation failed")
                else -> throw WemiException.CompilationException("Kotlin compilation failed: $compileResult")
            }
        }

        // Compile Java
        if (javaSources.isNotEmpty()) {
            val compiler = Keys.javaCompiler.get()
            val fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8) ?: throw WemiException("No standardFileManager")
            val writerSb = StringBuilder()
            val writer = StringBuilderWriter(writerSb)

            val sourcesOut = Keys.outputSourcesDirectory.get()
            sourcesOut.ensureEmptyDirectory()
            val headersOut = Keys.outputHeadersDirectory.get()
            headersOut.ensureEmptyDirectory()

            val pathSeparator = System.getProperty("path.separator", ":")
            val compilerOptions = ArrayList<String>()
            compilerFlags.use(JavaCompilerFlags.customFlags) {
                compilerOptions.addAll(it)
            }
            compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                if (it.isNotEmpty()) {
                    compilerOptions.add("-source")
                    compilerOptions.add(it)
                }
            }
            compilerFlags.use(JavaCompilerFlags.targetVersion) {
                if (it.isNotEmpty()) {
                    compilerOptions.add("-target")
                    compilerOptions.add(it)
                }
            }
            compilerFlags.use(JavaCompilerFlags.encoding) {
                if (it.isNotEmpty()) {
                    compilerOptions.add("-encoding")
                    compilerOptions.add(it)
                }
            }
            compilerOptions.add("-classpath")
            val classpathString = externalClasspath.joinToString(pathSeparator) { it.absolutePath }
            if (kotlinSources.isNotEmpty()) {
                compilerOptions.add(classpathString + pathSeparator + output.absolutePath)
            } else {
                compilerOptions.add(classpathString)
            }
            compilerOptions.add("-d")
            compilerOptions.add(output.absolutePath)
            compilerOptions.add("-s")
            compilerOptions.add(sourcesOut.absolutePath)
            compilerOptions.add("-h")
            compilerOptions.add(headersOut.absolutePath)

            val javaFiles = fileManager.getJavaFileObjectsFromFiles(javaSources.map { it.file.toFile() })

            val success = compiler.getTask(
                    writer,
                    fileManager,
                    JavaDiagnosticListener,
                    compilerOptions,
                    null,
                    javaFiles
            ).call()

            if (!writerSb.isBlank()) {
                val format = if (writerSb.contains('\n')) "\n{}" else "{}"
                if (success) {
                    JavacLOG.info(format, writerSb)
                } else {
                    JavacLOG.warn(format, writerSb)
                }
            }

            if (!success) {
                throw WemiException.CompilationException("Java compilation failed")
            }
        }

        output
    }

    val CompileKotlinJS: Value<Path> = {
        val output = Keys.outputJavascriptDirectory.get()
        output.ensureEmptyDirectory()

        val compilerFlags = Keys.compilerOptions.get()
        val kotlinSources = Keys.sources.getLocatedPaths(*KotlinSourceFileExtensions)

        val externalClasspath = LinkedHashSet<Path>()
        for (path in Keys.externalClasspath.getLocatedPathsForScope(Keys.scopesCompile.get())) {
            externalClasspath.add(path.classpathEntry)
        }
        for (path in Keys.generatedClasspath.get()) {
            externalClasspath.add(path.classpathEntry)
        }

        if (kotlinSources.isNotEmpty()) {
            val compiler = Keys.kotlinCompiler.get()

            val cacheFolder = output.resolveSibling(output.name + "-kotlin-js-cache")
            Files.createDirectories(cacheFolder)

            when (val compileResult = compiler.compile(KotlinCompiler.CompilationType.JS, kotlinSources, externalClasspath, output, cacheFolder, compilerFlags, KotlincLOG, null)) {
                KotlinCompiler.CompileExitStatus.OK -> {}
                KotlinCompiler.CompileExitStatus.CANCELLED -> throw WemiException.CompilationException("Kotlin compilation has been cancelled")
                KotlinCompiler.CompileExitStatus.COMPILATION_ERROR -> throw WemiException.CompilationException("Kotlin compilation failed")
                else -> throw WemiException.CompilationException("Kotlin compilation failed: $compileResult")
            }
        }

        output
    }

    val RunOptions: Value<List<String>> = {
        val options = WMutableList<String>()
        options.add("-ea")
        val debugPort = System.getenv("WEMI_RUN_DEBUG_PORT")?.toIntOrNull()
        if (debugPort != null) {
            options.add("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=$debugPort")
        }
        for ((key, value) in Keys.runSystemProperties.get()) {
            options.add("-D$key=$value")
        }
        options
    }

    val RunProcess: Value<ProcessBuilder> = {
        val javaExecutable = Keys.javaHome.get().javaExecutable
        val classpathEntries = LinkedHashSet<Path>()
        for (locatedFile in Keys.externalClasspath.getLocatedPathsForScope(Keys.scopesRun.get())) {
            classpathEntries.add(locatedFile.classpathEntry)
        }
        for (locatedFile in Keys.internalClasspath.get()) {
            classpathEntries.add(locatedFile.classpathEntry)
        }
        val main = Keys.mainClass.get()
        val directory = Keys.runDirectory.get()
        val options = Keys.runOptions.get().toMutableList()
        val arguments = Keys.runArguments.get()
        val environment = Keys.runEnvironment.get()

        prepareJavaProcess(javaExecutable, directory, classpathEntries, main, options, arguments, environment)
    }

    val Run: Value<ExitCode> = {
        expiresNow()
        ExitCode(runForegroundProcess(Keys.runProcess.get(), controlOutput = false))
    }

    val TestParameters: Value<TestParameters> = {
        val testParameters = wemi.test.TestParameters()
        testParameters.classpathRoots.add(Keys.outputClassesDirectory.get().absolutePath)

        read("class", "Include classes, whose fully classified name match this regex", StringValidator, ask=false)?.let {  classPattern ->
            testParameters.filterClassNamePatterns.included.add(classPattern)
        }

        testParameters
    }

    val Test: Value<TestReport> = {
        using(Configurations.testing) {
            val javaExecutable = Keys.javaHome.get().javaExecutable
            val directory = Keys.runDirectory.get()
            val options = Keys.runOptions.get()
            val environment = Keys.runEnvironment.get()

            // Testing classpath indeed contains all of these
            // (It is needed for example when there are two dependencies, one with provided scope, another with test scope.
            //  Combined, they have the provided scope, which therefore must be available on the test classpath.)
            val scopes = Keys.scopesTest.get()
            val externalClasspath = Keys.externalClasspath.getLocatedPathsForScope(scopes).mapTo(LinkedHashSet()){ it.classpathEntry }
            val internalClasspath = Keys.internalClasspath.get().mapTo(LinkedHashSet()){ it.classpathEntry }

            val classpathEntries = ArrayList<Path>(internalClasspath.size + externalClasspath.size + 1)
            classpathEntries.addAll(internalClasspath)
            classpathEntries.addAll(externalClasspath)
            classpathEntries.add(Magic.classpathFileOf(TEST_LAUNCHER_MAIN_CLASS)!!)

            val processBuilder = prepareJavaProcess(
                    javaExecutable, directory, classpathEntries,
                    TEST_LAUNCHER_MAIN_CLASS.name, options, emptyList(), environment)

            val testParameters = Keys.testParameters.get(*input) // Input passthrough

            val report = handleProcessForTesting(processBuilder, testParameters)
                    ?: throw WemiException("Test execution failed, see logs for more information", showStacktrace = false)

            expiresNow()
            report
        }
    }

    val TestOfAggregateProject: Value<TestReport> = {
        val resultReport = TestReport()
        var orderId = 0
        inProjectDependencies { dep ->
            if (dep.scope == ScopeAggregate) {
                val report = Keys.test.getOrElse(null)
                if (report != null) {
                    resultReport.putAll(report.withPrefixContainer("%3d-%s".format(orderId++, dep.project.name), dep.project.name))
                }
            }
        }
        resultReport
    }

    fun EvalScope.defaultArchiveFileName(suffix:String? = null, extension:String = "zip"):Path {
        val projectName = Keys.projectName.get().toSafeFileName()
        val projectVersion = Keys.projectVersion.get().toSafeFileName()
        val result = Keys.cacheDirectory.get() / "-archive/$projectName-$projectVersion${if (suffix == null) "" else "-$suffix"}.$extension"
        Files.createDirectories(result.parent)
        return result
    }

    val Archive: Value<Path> = {
        AssemblyOperation().use { assemblyOperation ->
            // Load data
            for (file in Keys.internalClasspath.get()) {
                assemblyOperation.addSource(file, true)
            }
            for (file in Keys.externalClasspath.getLocatedPathsForScope(setOf(ScopeAggregate))) {
                assemblyOperation.addSource(file, true)
            }

            val outputFile = defaultArchiveFileName(extension = "jar")
            assemblyOperation.assembly(
                    NoConflictStrategyChooser,
                    DefaultRenameFunction,
                    Keys.assemblyMapFilter.get(),
                    outputFile,
                    NoPrependData,
                    compress = true)

            expiresWith(outputFile)
            outputFile
        }
    }

    val ArchiveSources: Value<Path> = {
        AssemblyOperation().use { assemblyOperation ->
            // Load data
            for (file in Keys.sources.getLocatedPaths()) {
                assemblyOperation.addSource(file, true, extractJarEntries = false)
            }

            inProjectDependencies { dep ->
                if (dep.scope == ScopeAggregate) {
                    for (file in Keys.sources.getLocatedPaths()) {
                        assemblyOperation.addSource(file, true, extractJarEntries = false)
                    }
                }
            }

            val outputFile = defaultArchiveFileName("sources", "zip")
            assemblyOperation.assembly(
                    NoConflictStrategyChooser,
                    DefaultRenameFunction,
                    DefaultAssemblyMapFilter,
                    outputFile,
                    NoPrependData,
                    compress = true)

            expiresWith(outputFile)
            outputFile
        }
    }

    /**
     * Binding for [Keys.archive] to use when archiving documentation and no documentation is available.
     */
    val ArchiveDummyDocumentation: Value<Path> = {
        AssemblyOperation().use { assemblyOperation ->

            /*
            # No documentation available

            |Group        | Name | Version |
            |:-----------:|:----:|:-------:|
            |com.whatever | Pear | 1.3     |

            *Built by Wemi 1.2*
            *Current date*
             */

            val groupHeading = "Group"
            val projectGroup = Keys.projectGroup.getOrElse("-")
            val groupWidth = maxOf(groupHeading.length, projectGroup.length) + 2

            val nameHeading = "Name"
            val projectName = Keys.projectName.getOrElse("-")
            val nameWidth = maxOf(nameHeading.length, projectName.length) + 2

            val versionHeading = "Version"
            val projectVersion = Keys.projectVersion.getOrElse("-")
            val versionWidth = maxOf(versionHeading.length, projectVersion.length) + 2

            val md = StringBuilder()
            md.append("# No documentation available\n\n")
            md.append('|').appendCentered(groupHeading, groupWidth, ' ')
                    .append('|').appendCentered(nameHeading, nameWidth, ' ')
                    .append('|').appendCentered(versionHeading, versionWidth, ' ').append("|\n")

            md.append("|:").appendTimes('-', groupWidth - 2)
                    .append(":|:").appendTimes('-', nameWidth - 2)
                    .append(":|:").appendTimes('-', versionWidth - 2).append(":|\n")

            md.append('|').appendCentered(projectGroup, groupWidth, ' ')
                    .append('|').appendCentered(projectName, nameWidth, ' ')
                    .append('|').appendCentered(projectVersion, versionWidth, ' ').append("|\n")

            md.append("\n*Built by Wemi $WemiVersion*\n")
            md.append("*").append(ZonedDateTime.now()).append("*\n")

            assemblyOperation.addSource(
                    "DOCUMENTATION.MD",
                    md.toString().toByteArray(Charsets.UTF_8),
                    true)

            val outputFile = defaultArchiveFileName("docs", "zip")
            assemblyOperation.assembly(
                    NoConflictStrategyChooser,
                    DefaultRenameFunction,
                    DefaultAssemblyMapFilter,
                    outputFile,
                    NoPrependData,
                    compress = true)

            expiresWith(outputFile)
            outputFile
        }
    }

    val ArchiveJavadocOptions: Value<List<String>> = {
        val options = WMutableList<String>()

        val compilerFlags = Keys.compilerOptions.get()
        var javaVersionString:String? = null
        compilerFlags.use(JavaCompilerFlags.sourceVersion) {
            options.add("-source")
            options.add(it)
            javaVersionString = it
        }
        if (javaVersionString == null) {
            javaVersionString = compilerFlags.getOrNull(JavaCompilerFlags.targetVersion)
        }

        val javaVersion = parseJavaVersion(javaVersionString)

        options.add("-link")
        options.add(javadocUrl(javaVersion))

        options
    }

    private val ARCHIVE_JAVADOC_LOG = LoggerFactory.getLogger("ArchiveJavadoc")
    private val ARCHIVE_JAVADOC_DIAGNOSTIC_LISTENER = createJavaObjectFileDiagnosticLogger(ARCHIVE_JAVADOC_LOG)
    private val ARCHIVE_JAVADOC_OUTPUT_READER = LineReadingWriter { line ->
        if (line.isNotBlank()) {
            ARCHIVE_JAVADOC_LOG.warn("> {}", line)
        }
    }

    val ArchiveJavadoc: Value<Path> = archive@{
        val sourceFiles = Keys.sources.getLocatedPaths(*JavaSourceFileExtensions)

        if (sourceFiles.isEmpty()) {
            ARCHIVE_JAVADOC_LOG.info("No source files for Javadoc, creating dummy documentation instead")
            return@archive ArchiveDummyDocumentation()
        }

        val documentationTool = ToolProvider.getSystemDocumentationTool()!!
        val fileManager = documentationTool.getStandardFileManager(ARCHIVE_JAVADOC_DIAGNOSTIC_LISTENER, Locale.ROOT, Charsets.UTF_8)
        val sourceRoots = HashSet<File>()
        sourceFiles.mapNotNullTo(sourceRoots) { it.root?.toFile() }
        fileManager.setLocation(StandardLocation.SOURCE_PATH, sourceRoots)
        val javadocOutput = Keys.cacheDirectory.get() / "javadoc-${Keys.projectName.get().toSafeFileName('_')}"
        javadocOutput.ensureEmptyDirectory()
        fileManager.setLocation(DocumentationTool.Location.DOCUMENTATION_OUTPUT, listOf(javadocOutput.toFile()))

        // Setup classpath
        fileManager.setLocation(StandardLocation.CLASS_PATH, Keys.externalClasspath.getLocatedPathsForScope(Keys.scopesCompile.get()).map { it.classpathEntry.toFile() })

        // Try to specify doclet path explicitly
        val toolsJar = Keys.javaHome.get().toolsJar
        if (toolsJar != null) {
            fileManager.setLocation(DocumentationTool.Location.DOCLET_PATH, listOf(toolsJar.toFile()))
        }

        var failOnError = false
        val options = Keys.archiveJavadocOptions.get().filter {
            if (it == "-Wemi-fail-on-error") {
                failOnError = true
                false
            } else true
        }

        val docTask = documentationTool.getTask(
                ARCHIVE_JAVADOC_OUTPUT_READER, fileManager,
                ARCHIVE_JAVADOC_DIAGNOSTIC_LISTENER, null,
                options, fileManager.getJavaFileObjectsFromFiles(sourceFiles.map { it.file.toFile() }))

        docTask.setLocale(Locale.ROOT)
        val result = docTask.call()
        ARCHIVE_JAVADOC_OUTPUT_READER.close()

        if (!result) {
            if (failOnError) {
                throw WemiException("Failed to package javadoc", showStacktrace = false)
            } else {
                ARCHIVE_JAVADOC_LOG.warn("There were errors when building Javadoc")
            }
        }

        val locatedFiles = ArrayList<LocatedPath>()
        constructLocatedFiles(javadocOutput, locatedFiles)

        AssemblyOperation().use { assemblyOperation ->
            // Load data
            for (file in locatedFiles) {
                assemblyOperation.addSource(file, own = true, extractJarEntries = false)
            }

            val outputFile = defaultArchiveFileName("docs", "jar")
            assemblyOperation.assembly(
                    NoConflictStrategyChooser,
                    DefaultRenameFunction,
                    DefaultAssemblyMapFilter,
                    outputFile,
                    NoPrependData,
                    compress = true)

            expiresWith(outputFile)
            outputFile
        }
    }

    val ArchiveDokkaOptions: Value<DokkaOptions> = {
        val compilerOptions = Keys.compilerOptions.get()

        val options = DokkaOptions()

        for (sourceRoot in Keys.sources.getLocatedPaths().mapNotNullTo(HashSet()) { it.root?.toAbsolutePath() }) {
            options.sourceRoots.add(DokkaOptions.SourceRoot(sourceRoot))
        }

        options.moduleName = compilerOptions.getOrNull(KotlinCompilerFlags.moduleName) ?: Keys.projectName.get()
        val javaVersion = parseJavaVersion(
                compilerOptions.getOrNull(JavaCompilerFlags.sourceVersion)
                        ?: compilerOptions.getOrNull(JavaCompilerFlags.targetVersion)
                        ?: compilerOptions.getOrNull(KotlinJVMCompilerFlags.jvmTarget))
        if (javaVersion != null) {
            options.jdkVersion = javaVersion
        }
        options.externalDocumentationLinks.add(DokkaOptions.ExternalDocumentation(javadocUrl(javaVersion)))

        options.impliedPlatforms.add("JVM")

        options
    }

    private val DokkaFatJar = listOf(Dependency(DependencyId("org.jetbrains.dokka", "dokka-fatjar", "0.9.15"), exclusions = WemiBundledLibrariesExclude))

    val ArchiveDokkaInterface: Value<DokkaInterface> = {
        val artifacts = resolveDependencyArtifacts(DokkaFatJar, listOf(JCenter), progressListener)?.toMutableList()
                ?: throw IllegalStateException("Failed to retrieve kotlin compiler library")

        Keys.javaHome.get().toolsJar?.let { artifacts.add(it) }

        ARCHIVE_DOKKA_LOG.trace("Classpath for Dokka: {}", artifacts)

        val implementationClassName = "wemi.documentation.impl.DokkaInterfaceImpl"
        /** Loads artifacts normally. */
        val dependencyClassLoader = URLClassLoader(artifacts.map { it.toUri().toURL() }.toTypedArray(), Magic.WemiDefaultClassLoader)
        /** Makes sure that the implementation class is loaded in a class loader that has artifacts available. */
        val forceClassLoader = EnclaveClassLoader(emptyArray(), dependencyClassLoader,
                implementationClassName, // Own entry point
                "org.jetbrains.dokka.") // Force loading all of Dokka in here

        val clazz = Class.forName(implementationClassName, true, forceClassLoader)

        clazz.newInstance() as DokkaInterface
    }

    private val ARCHIVE_DOKKA_LOG = LoggerFactory.getLogger("ArchiveDokka")
    val ArchiveDokka: Value<Path> = archive@{
        val options = Keys.archiveDokkaOptions.get()

        if (options.sourceRoots.isEmpty()) {
            ARCHIVE_DOKKA_LOG.info("No source files for Dokka, creating dummy documentation instead")
            return@archive ArchiveDummyDocumentation()
        }

        val cacheDirectory = Keys.cacheDirectory.get()
        val dokkaOutput = cacheDirectory / "dokka-${Keys.projectName.get().toSafeFileName('_')}"
        dokkaOutput.ensureEmptyDirectory()

        val packageListCacheFolder = cacheDirectory / "dokka-package-list-cache"

        val externalClasspath = Keys.externalClasspath.getLocatedPathsForScope(Keys.scopesCompile.get()).mapTo(LinkedHashSet()) { it.classpathEntry }

        val dokka = Keys.archiveDokkaInterface.get()

        dokka.execute(externalClasspath, dokkaOutput, packageListCacheFolder, options, ARCHIVE_DOKKA_LOG)

        val locatedFiles = ArrayList<LocatedPath>()
        constructLocatedFiles(dokkaOutput, locatedFiles)

        AssemblyOperation().use { assemblyOperation ->
            // Load data
            for (file in locatedFiles) {
                assemblyOperation.addSource(file, own = true, extractJarEntries = false)
            }

            val outputFile = defaultArchiveFileName("docs", "jar")
            assemblyOperation.assembly(
                    NoConflictStrategyChooser,
                    DefaultRenameFunction,
                    DefaultAssemblyMapFilter,
                    outputFile,
                    NoPrependData,
                    compress = true)

            expiresWith(outputFile)
            outputFile
        }
    }

    private val PUBLISH_MODEL_LOG = LoggerFactory.getLogger("PublishModelM2")
    /**
     * Creates Maven2 compatible pom.xml-like [InfoNode].
     */
    val PublishModelM2: Value<InfoNode> = {
        /*
        Commented out code is intended as example when customizing own publishMetadata pom.xml.
        
        Full pom.xml specification can be obtained here:
        https://maven.apache.org/ref/3.5.2/maven-model/maven.html
        
        Mandatory fields are described here:
        https://maven.apache.org/project-faq.html
        */
        InfoNode("project") {
            newChild("modelVersion", "4.0.0")
            attribute("xmlns", "http://maven.apache.org/POM/4.0.0")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute("xsi:schemaLocation", "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd")

            newChild("groupId", Keys.projectGroup.get()) // MANDATORY
            newChild("artifactId", Keys.projectName.get()) // MANDATORY
            newChild("version", Keys.projectVersion.get()) // MANDATORY

            newChild("packaging", "jar") // MANDATORY (defaults to jar)

            newChild("name") // MANDATORY, filled by user
            newChild("description") // MANDATORY, filled by user
            newChild("url") // MANDATORY, filled by user
            //newChild("inceptionYear", "2018")

            /* Example
            newChild("organization") {
                newChild("name", "ACME Codes Inc.")
                newChild("url", "https://acme.codes.example.com")
            }
            */

            newChild("licenses") // Mandatory, filled by user
            /*{ Example
                newChild("license") {
                    newChild("name", "MyLicense")
                    newChild("url", "https://my.license.example.com")
                    newChild("distribution", "repo") // or manual, if user has to download this *dependency* manually
                    newChild("comments") // Optional comments
                }
            }*/

            /* Example
            newChild("developers") { // People directly developing the project
                newChild("developer") {
                    newChild("id", "Darkyenus") // SCM handle (for example Github login)
                    newChild("name", "Jan Polák") // Full name
                    newChild("email", "darkyenus@example.com")
                    newChild("url", "https://example.com")
                    newChild("organization", "ACME Codes Inc.")
                    newChild("organizationUrl", "https://acme.codes.example.com")
                    newChild("roles") {
                        newChild("role", "Developer")
                        newChild("role", "Creator of Wemi")
                    }
                    newChild("timezone", "Europe/Prague") // Or number, such as +1 or -14
                    newChild("properties") {
                        newChild("IRC", "Darkyenus") // Any key-value pairs
                    }
                }
            }
            newChild("contributors") { // People contributing to the project, but without SCM access
                newChild("contributor") {
                    // Same as "developer", but without "id"
                }
            }
            */

            /* Example
            newChild("scm") {
                // See https://maven.apache.org/scm/scms-overview.html
                newChild("connection", "scm:git:https://github.com/Darkyenus/WEMI")
                newChild("developerConnection", "scm:git:https://github.com/Darkyenus/WEMI")
                newChild("tag", "HEAD")
                newChild("url", "https://github.com/Darkyenus/WEMI")
            }
            */

            /* Example
            newChild("issueManagement") {
                newChild("system", "GitHub Issues")
                newChild("url", "https://github.com/Darkyenus/WEMI/issues")
            }
            */

            newChild("dependencies") {
                for (dependency in Keys.libraryDependencies.get()) {
                    newChild("dependency") {
                        newChild("groupId", dependency.dependencyId.group)
                        newChild("artifactId", dependency.dependencyId.name)
                        newChild("version", dependency.dependencyId.version)
                        if (dependency.dependencyId.type != DEFAULT_TYPE) {
                            newChild("type", dependency.dependencyId.type)
                        }
                        if (dependency.dependencyId.classifier != NoClassifier) {
                            newChild("classifier", dependency.dependencyId.classifier)
                        }
                        if (dependency.scope != DEFAULT_SCOPE) {
                            newChild("scope", dependency.scope)
                        }
                        if (dependency.optional != DEFAULT_OPTIONAL) {
                            newChild("optional", dependency.optional.toString())
                        }
                        newChild("exclusions") {
                            for (exclusion in dependency.exclusions) {
                                // Check if Maven compatible (only group and name is set)
                                val mavenCompatible = exclusion.group != null
                                        && exclusion.name != null
                                        && exclusion.version == null
                                        && exclusion.classifier == null
                                        && exclusion.type == null

                                if (mavenCompatible) {
                                    newChild("exclusion") {
                                        newChild("groupId", exclusion.group!!)
                                        newChild("artifactId", exclusion.name!!)
                                    }
                                } else {
                                    PUBLISH_MODEL_LOG.warn("Exclusion {} on {} is not supported by pom.xml and will be omitted", exclusion, dependency.dependencyId)
                                }
                            }
                        }
                    }
                }
            }

            newChild("repositories") {
                for (repository in Keys.repositories.get()) {
                    if (repository == MavenCentral) {
                        // Added by default
                        continue
                    }
                    if (repository.local) {
                        PUBLISH_MODEL_LOG.warn("Omitting repository {} from published pom, it is local", repository)
                        continue
                    }

                    newChild("repository") {
                        /* Extra info we don't collect
                        newChild("releases") {
                            newChild("enabled", "true") // Use for downloading releases?
                            newChild("updatePolicy") // always, daily (default), interval:<minutes>, never
                            newChild("checksumPolicy") // ignore, fail, warn (default)
                        }
                        newChild("snapshots") {
                            // Like "releases"
                        }
                        */
                        newChild("id", repository.name)
                        //newChild("name", "Human readable name")
                        newChild("url", repository.url.toString())
                    }
                }
            }
        }
    }

    val PublishM2: Value<Path> = {
        val repository = Keys.publishRepository.get()
        val metadata = Keys.publishMetadata.get()
        val artifacts = Keys.publishArtifacts.get()

        val result = publish(repository, metadata, artifacts)
        expiresWith(result)
        result
    }

    /**
     * Creates a default value for [Keys.externalSources] and [Keys.externalDocs]
     */
    fun externalClasspathWithClassifier(classifier:String):Value<List<Path>> = {
        // Authoritative repositories, so the search stops as soon as one value is found - malicious sources are not a problem, this is only for reference
        val repositories = Keys.repositories.get().mapTo(HashSet()) { if (!it.authoritative) it.copy(authoritative = true) else it }
        val mapper = classifierAppendingLibraryDependencyProjectMapper(classifier)

        val libraryDependencies = Keys.libraryDependencies.get().map(mapper)
        val resolved = resolveDependencyArtifacts(libraryDependencies, repositories, progressListener, mapper, allowErrors = true) ?: emptyList()
        val unmanaged = classifierAppendingClasspathModifier(classifier).invoke(this, Keys.unmanagedDependencies.get())

        resolved.map { it } + unmanaged.map { it.classpathEntry }
    }

    val Assembly: Value<Path> = {
        AssemblyOperation().use { assemblyOperation ->
            // Load data
            for (file in Keys.internalClasspath.get()) {
                assemblyOperation.addSource(file, true, extractJarEntries = false)
            }
            ext@for ((file, scope) in Keys.externalClasspath.get()) {
                val runScopes = Keys.scopesRun.get()
                assemblyOperation.addSource(file, when (scope) {
                    in runScopes -> scope == ScopeAggregate
                    else -> continue@ext
                }, extractJarEntries = true)
            }

            val outputFile = Keys.assemblyOutputFile.get()
            assemblyOperation.assembly(Keys.assemblyMergeStrategy.get(),
                    Keys.assemblyRenameFunction.get(),
                    Keys.assemblyMapFilter.get(),
                    outputFile,
                    Keys.assemblyPrependData.get(),
                    compress = true)

            expiresWith(outputFile)

            outputFile
        }
    }
}