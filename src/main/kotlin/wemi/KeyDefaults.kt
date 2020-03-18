@file:Suppress("MemberVisibilityCanBePrivate")

package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.boot.CLI
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
import wemi.dependency.internal.publish
import wemi.dependency.joinClassifiers
import wemi.dependency.resolveDependencies
import wemi.dependency.resolveDependencyArtifacts
import wemi.dependency.scopeIn
import wemi.documentation.DokkaInterface
import wemi.documentation.DokkaOptions
import wemi.publish.InfoNode
import wemi.test.TEST_LAUNCHER_MAIN_CLASS
import wemi.test.TestParameters
import wemi.test.TestReport
import wemi.test.handleProcessForTesting
import wemi.util.CliStatusDisplay.Companion.withStatus
import wemi.util.CycleChecker
import wemi.util.EnclaveClassLoader
import wemi.util.LineReadingWriter
import wemi.util.LocatedPath
import wemi.util.Magic
import wemi.util.Partial
import wemi.util.absolutePath
import wemi.util.appendCentered
import wemi.util.appendTimes
import wemi.util.constructLocatedFiles
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.exists
import wemi.util.format
import wemi.util.isDirectory
import wemi.util.name
import wemi.util.parseJavaVersion
import wemi.util.pathExtension
import wemi.util.pathWithoutExtension
import wemi.util.prettyPrint
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
    fun EvalScope.inProjectDependencies(aggregate:Boolean?, withScope:Set<wemi.dependency.DepScope> = emptySet(), operation:EvalScope.(dep:ProjectDependency)->Unit) {
        inProjectDependencies_CircularDependencyProtection.block(this.scope, failure = { loop ->
            throw WemiException("Cyclic dependencies in projectDependencies are not allowed (${loop.joinToString(" -> ")})", showStacktrace = false)
        }, action = {
            val projectDependencies = Keys.projectDependencies.get()

            for (projectDependency in projectDependencies) {
                if (aggregate != null && aggregate != projectDependency.aggregate) {
                    continue
                }

                if (!(projectDependency.scope scopeIn withScope)) {
                    continue
                }

                // Enter a different scope and perform the operation
                using(projectDependency.project.scopeFor(projectDependency.configurations.toList() + scope.configurations)) {
                    operation(projectDependency)
                }
            }
        })
    }

    private val ClasspathResolution_LOG = LoggerFactory.getLogger("ClasspathResolution")
    val ExternalClasspath: Value<List<LocatedPath>> = {
        val result = WMutableList<LocatedPath>()

        val resolved = Keys.resolvedLibraryDependencies.get()
        if (!resolved.complete) { // TODO(jp): Do not check if complete yet, allow incomplete dependency resolution if the errors are in un-needed dependencies (due to scope)
            val message = StringBuilder()
            message.append("Failed to resolve all artifacts")
                    .format() // Required, because printing it with pre-set color would bleed into the pretty-printed values
                    .append('\n')
                    .append(resolved.value.prettyPrint())
            throw WemiException(message.toString(), showStacktrace = false)
        }

        val scopes = Keys.resolvedLibraryScopes.get()
        for ((_, resolvedDependency) in resolved.value) {
            if (resolvedDependency.scope scopeIn scopes) {
                result.add(LocatedPath(resolvedDependency.artifact?.path ?: continue))
            }
        }

        inProjectDependencies(null, scopes) { projectDependency ->
            ClasspathResolution_LOG.debug("Resolving project dependency on {}", this)
            result.addAll(Keys.externalClasspath.get())
            if (!projectDependency.aggregate) {
                result.addAll(Keys.internalClasspath.get())
            }
        }

        val unmanaged = Keys.unmanagedDependencies.get()
        result.addAll(unmanaged)

        result
    }

    fun internalClasspath(compile:Boolean): Value<List<LocatedPath>> = {
        val classpath = WMutableList<LocatedPath>()
        if (compile) {
            classpath.addAll(Keys.generatedClasspath.get())
            constructLocatedFiles(Keys.compile.get(), classpath)
        }
        classpath.addAll(Keys.resources.getLocatedPaths())

        inProjectDependencies(true, Keys.resolvedLibraryScopes.get()) {
            ClasspathResolution_LOG.debug("Resolving internal project dependency on {}", this)
            classpath.addAll(Keys.internalClasspath.get())
        }

        classpath
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
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
            output.ensureEmptyDirectory()

            val javaSources = Keys.sources.getPaths(*JavaSourceFileExtensions)

            val externalClasspath = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry })
            externalClasspath.addAll(Keys.generatedClasspath.get().map { it.classpathEntry })

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
                val classpathString = externalClasspath.joinToString(pathSeparator) { it.absolutePath }
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
    }

    val CompileJavaKotlin: Value<Path> = {
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
            output.ensureEmptyDirectory()

            val compilerFlags = Keys.compilerOptions.get()
            val javaSources = Keys.sources.getLocatedPaths(*JavaSourceFileExtensions)
            val kotlinSources = Keys.sources.getLocatedPaths(*KotlinSourceFileExtensions)

            val externalClasspath = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry })
            externalClasspath.addAll(Keys.generatedClasspath.get().map { it.classpathEntry })

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
    }

    val RunOptions: Value<List<String>> = {
        val options = WMutableList<String>()
        options.add("-ea")
        val debugPort = System.getenv("WEMI_RUN_DEBUG_PORT")?.toIntOrNull()
        if (debugPort != null) {
            options.add("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=$debugPort")
        }
        options
    }

    /** Implements the launch of JVM for [Run] and [RunMain]. */
    private fun EvalScope.doRun(mainClass:String):Int {
        val javaExecutable = Keys.javaExecutable.get()
        val classpathEntries = LinkedHashSet<Path>()
        for (locatedFile in Keys.externalClasspath.get()) {
            classpathEntries.add(locatedFile.classpathEntry)
        }
        for (locatedFile in Keys.internalClasspath.get()) {
            classpathEntries.add(locatedFile.classpathEntry)
        }
        val directory = Keys.runDirectory.get()
        val options = Keys.runOptions.get()
        val arguments = Keys.runArguments.get()

        val dry = read("dry", "Only print the command to run the program, instead of running it", BooleanValidator, ask = false) ?: false
        if (dry) {
            val command = wemi.run.prepareJavaProcessCommand(javaExecutable, classpathEntries, mainClass, options, arguments)
            println(command.joinToString(" "))
            return 0
        }

        val processBuilder = wemi.run.prepareJavaProcess(javaExecutable, directory, classpathEntries,
                mainClass, options, arguments)

        // Separate process output from Wemi output
        return CLI.MessageDisplay.withStatus(false) {
            println()
            val process = processBuilder.start()
            val result = CLI.forwardSignalsTo(process) { process.waitFor() }
            println()
            result
        }
    }

    val Run: Value<Int> = {
        using(Configurations.running) {
            expiresNow()
            doRun(Keys.mainClass.get())
        }
    }

    val RunMain: Value<Int> = {
        using(Configurations.running) {
            val mainClass = read("main", "Main class to start", ClassNameValidator)
                    ?: throw WemiException("Main class not specified", showStacktrace = false)
            expiresNow()
            doRun(mainClass)
        }
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
        using(Configurations.running, Configurations.testing) {
            val javaExecutable = Keys.javaExecutable.get()
            val directory = Keys.runDirectory.get()
            val options = Keys.runOptions.get()

            val externalClasspath = Keys.externalClasspath.get().asSequence().map { it.classpathEntry }.distinct().toList()
            val internalClasspath = Keys.internalClasspath.get().asSequence().map { it.classpathEntry }.distinct().toList()

            val classpathEntries = ArrayList<Path>(internalClasspath.size + externalClasspath.size + 1)
            classpathEntries.addAll(internalClasspath)
            classpathEntries.addAll(externalClasspath)
            classpathEntries.add(Magic.classpathFileOf(TEST_LAUNCHER_MAIN_CLASS)!!)

            val processBuilder = wemi.run.prepareJavaProcess(
                    javaExecutable, directory, classpathEntries,
                    TEST_LAUNCHER_MAIN_CLASS.name, options, emptyList())

            val testParameters = Keys.testParameters.get(*input) // Input passthrough

            val report = handleProcessForTesting(processBuilder, testParameters)
                    ?: throw WemiException("Test execution failed, see logs for more information", showStacktrace = false)

            expiresNow()
            report
        }
    }

    val TestOfAggregateProject: Value<TestReport> = {
        val resultReport = TestReport()
        inProjectDependencies(true) {
            resultReport.putAll(Keys.test.get())
        }
        resultReport
    }

    val Archive: Value<Path> = {
        using(Configurations.archiving) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.internalClasspath.get()) {
                    assemblyOperation.addSource(file, true)
                }

                val outputFile = Keys.archiveOutputFile.get()
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
    }

    val ArchiveSources: Value<Path> = {
        using(Configurations.archivingSources) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.sources.getLocatedPaths()) {
                    assemblyOperation.addSource(file, true, extractJarEntries = false)
                }

                inProjectDependencies(true) {
                    for (file in Keys.sources.getLocatedPaths()) {
                        assemblyOperation.addSource(file, true, extractJarEntries = false)
                    }
                }

                val outputFile = Keys.archiveOutputFile.get()
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
    }

    /**
     * Binding for [Keys.archive] to use when archiving documentation and no documentation is available.
     */
    val ArchiveDummyDocumentation: Value<Path> = {
        using(Configurations.archivingDocs) {
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

                val outputFile = Keys.archiveOutputFile.get()
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
    }

    /**
     * When java is run from "jre" part of the JDK install, "tools.jar" is not in the classpath by default.
     * This can locate the tools.jar in [Keys.javaHome] for explicit loading.
     */
    private fun jdkToolsJar(javaHome:Path):Path? {
        return javaHome.resolve("lib/tools.jar").takeIf { it.exists() }
                ?: (if (javaHome.name == "jre") javaHome.resolve("../lib/tools.jar").takeIf { it.exists() } else null)
    }

    /** Return URL at which Java SE Javadoc is hosted for given [javaVersion]. */
    private fun javadocUrl(javaVersion:Int?):String {
        val version = javaVersion ?: 13 // = newest
        return when {
            // These versions don't have API uploaded, so fall back to 1.5 (first one that is online)
            version <= 5 -> "https://docs.oracle.com/javase/1.5.0/docs/api/"
            version in 6..10 -> "https://docs.oracle.com/javase/${version}/docs/api/"
            else -> "https://docs.oracle.com/en/java/javase/${version}/docs/api/"
        }
    }

    val ArchiveJavadocOptions: Value<List<String>> = {
        using(Configurations.archivingDocs) {
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
    }

    private val ARCHIVE_JAVADOC_LOG = LoggerFactory.getLogger("ArchiveJavadoc")
    private val ARCHIVE_JAVADOC_DIAGNOSTIC_LISTENER = createJavaObjectFileDiagnosticLogger(ARCHIVE_JAVADOC_LOG)
    private val ARCHIVE_JAVADOC_OUTPUT_READER = LineReadingWriter { line ->
        if (line.isNotBlank()) {
            ARCHIVE_JAVADOC_LOG.warn("> {}", line)
        }
    }

    val ArchiveJavadoc: Value<Path> = {
        using(Configurations.archivingDocs) {
            val sourceFiles = Keys.sources.getLocatedPaths(*JavaSourceFileExtensions)

            if (sourceFiles.isEmpty()) {
                ARCHIVE_JAVADOC_LOG.info("No source files for Javadoc, creating dummy documentation instead")
                return@using ArchiveDummyDocumentation()
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
            fileManager.setLocation(StandardLocation.CLASS_PATH, Keys.externalClasspath.get().map { it.classpathEntry.toFile() })

            // Try to specify doclet path explicitly
            val toolsJar = jdkToolsJar(Keys.javaHome.get())
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

                val outputFile = Keys.archiveOutputFile.get()
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
    }

    val ArchiveDokkaOptions: Value<DokkaOptions> = {
        val compilerOptions = Keys.compilerOptions.get()

        val options = DokkaOptions()

        for (sourceRoot in Keys.sources.getLocatedPaths()) {
            options.sourceRoots.add(DokkaOptions.SourceRoot(sourceRoot.root ?: continue))
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
        val javaHome = Keys.javaHome.get()
        val artifacts = resolveDependencyArtifacts(DokkaFatJar, listOf(JCenter), progressListener)?.toMutableList()
                ?: throw IllegalStateException("Failed to retrieve kotlin compiler library")

        jdkToolsJar(javaHome)?.let { artifacts.add(it) }

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
    val ArchiveDokka: Value<Path> = {
        using(Configurations.archivingDocs) {
            val options = Keys.archiveDokkaOptions.get()

            if (options.sourceRoots.isEmpty()) {
                ARCHIVE_DOKKA_LOG.info("No source files for Dokka, creating dummy documentation instead")
                return@using ArchiveDummyDocumentation()
            }

            val cacheDirectory = Keys.cacheDirectory.get()
            val dokkaOutput = cacheDirectory / "dokka-${Keys.projectName.get().toSafeFileName('_')}"
            dokkaOutput.ensureEmptyDirectory()

            val packageListCacheFolder = cacheDirectory / "dokka-package-list-cache"

            val externalClasspath = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry })

            val dokka = Keys.archiveDokkaInterface.get()

            dokka.execute(externalClasspath, dokkaOutput, packageListCacheFolder, options, ARCHIVE_DOKKA_LOG)

            val locatedFiles = ArrayList<LocatedPath>()
            constructLocatedFiles(dokkaOutput, locatedFiles)

            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in locatedFiles) {
                    assemblyOperation.addSource(file, own = true, extractJarEntries = false)
                }

                val outputFile = Keys.archiveOutputFile.get()
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
    }

    private val PUBLISH_MODEL_LOG = LoggerFactory.getLogger("PublishModelM2")
    /**
     * Creates Maven2 compatible pom.xml-like [InfoNode].
     * 
     * [Configurations.publishing] scope is applied at [Keys.publish], so this does not handle it.
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
        using(Configurations.publishing) {
            val repository = Keys.publishRepository.get()
            val metadata = Keys.publishMetadata.get()
            val artifacts = Keys.publishArtifacts.get()

            val result = publish(repository, metadata, artifacts)
            expiresWith(result)
            result
        }
    }

    val Assembly: Value<Path> = {
        using(Configurations.assembling) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.internalClasspath.get()) {
                    assemblyOperation.addSource(file, true, extractJarEntries = false)
                }
                for (file in Keys.externalClasspath.get()) {
                    assemblyOperation.addSource(file, false, extractJarEntries = true)
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
}