@file:Suppress("MemberVisibilityCanBePrivate")

package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.Configurations.archiving
import wemi.Configurations.assembling
import wemi.Configurations.compilingJava
import wemi.Configurations.compilingKotlin
import wemi.Configurations.publishing
import wemi.assembly.*
import wemi.boot.WemiBuildScript
import wemi.boot.WemiBundledLibrariesExclude
import wemi.collections.WList
import wemi.collections.WMutableList
import wemi.collections.WMutableSet
import wemi.collections.WSet
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.compile.internal.MessageLocation
import wemi.compile.internal.render
import wemi.dependency.*
import wemi.dependency.Repository.M2.Companion.joinClassifiers
import wemi.documentation.DokkaInterface
import wemi.documentation.DokkaOptions
import wemi.publish.InfoNode
import wemi.test.TEST_LAUNCHER_MAIN_CLASS
import wemi.test.TestParameters
import wemi.test.TestReport
import wemi.test.handleProcessForTesting
import wemi.util.*
import java.io.BufferedReader
import java.net.URI
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.util.*
import javax.tools.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet

/**
 * Contains default values bound to keys.
 *
 * This includes implementations of most tasks.
 */
object KeyDefaults {

    val SourceRootsJavaKotlin: BoundKeyValue<WSet<Path>> = {
        val bases = Keys.sourceBases.get()
        val roots = WMutableSet<Path>()
        for (base in bases) {
            roots.add(base / "kotlin")
            roots.add(base / "java")
        }
        roots
    }

    val ResourceRoots: BoundKeyValue<WSet<Path>> = {
        val bases = Keys.sourceBases.get()
        val roots = WMutableSet<Path>()
        for (base in bases) {
            roots.add(base / "resources")
        }
        roots
    }

    val SourceFiles: BoundKeyValue<WList<LocatedFile>> = {
        val roots = Keys.sourceRoots.get()
        val extensions = Keys.sourceExtensions.get()
        val result = WMutableList<LocatedFile>()

        for (root in roots) {
            constructLocatedFiles(root, result) { it.name.pathHasExtension(extensions) }
        }

        result
    }

    val ResourceFiles: BoundKeyValue<WList<LocatedFile>> = {
        val roots = Keys.resourceRoots.get()
        val result = WMutableList<LocatedFile>()

        for (root in roots) {
            constructLocatedFiles(root, result)
        }

        result
    }

    /**
     * Create value for [Keys.libraryDependencyProjectMapper] that appends given classifier to sources.
     */
    fun classifierAppendingLibraryDependencyProjectMapper(appendClassifier:String):(Dependency) -> Dependency = {
        (projectId, exclusions): Dependency ->
            val classifier = joinClassifiers(projectId.attribute(Repository.M2.Classifier), appendClassifier)!!
            val sourcesProjectId = projectId.copy(attributes = projectId.attributes + (Repository.M2.Classifier to classifier))
            Dependency(sourcesProjectId, exclusions)
        }


    val ResolvedLibraryDependencies: BoundKeyValue<Partial<Map<DependencyId, ResolvedDependency>>> = {
        val repositories = Keys.repositoryChain.get()
        val resolved = mutableMapOf<DependencyId, ResolvedDependency>()
        val complete = DependencyResolver.resolve(resolved, Keys.libraryDependencies.get(), repositories, Keys.libraryDependencyProjectMapper.get())
        Partial(resolved, complete)
    }

    private val ResolvedProjectDependencies_CircularDependencyProtection = CycleChecker<Scope>()
    fun resolvedProjectDependencies(aggregate:Boolean?): BoundKeyValue<WList<LocatedFile>> = {
        ResolvedProjectDependencies_CircularDependencyProtection.block(this, failure = {
            //TODO Show cycle
            throw WemiException("Cyclic dependencies in projectDependencies are not allowed", showStacktrace = false)
        }, action = {
            val result = WMutableList<LocatedFile>()
            val projectDependencies = Keys.projectDependencies.get()

            for (projectDependency in projectDependencies) {
                if (aggregate != null && aggregate != projectDependency.aggregate) {
                    continue
                }

                // Enter a different scope
                projectDependency.project.evaluate(*projectDependency.configurations) {
                    ExternalClasspath_LOG.debug("Resolving project dependency on {}", this)
                    result.addAll(Keys.externalClasspath.get())
                    result.addAll(Keys.internalClasspath.get())
                }
            }
            result
        })
    }

    private val ExternalClasspath_LOG = LoggerFactory.getLogger("ProjectDependencyResolution")
    val ExternalClasspath: BoundKeyValue<WList<LocatedFile>> = {
        val result = WMutableList<LocatedFile>()

        val resolved = Keys.resolvedLibraryDependencies.get()
        if (!resolved.complete) {
            throw WemiException("Failed to resolve all artifacts\n${resolved.value.prettyPrint(null)}", showStacktrace = false)
        }
        for ((_, resolvedDependency) in resolved.value) {
            result.add(LocatedFile(resolvedDependency.artifact ?: continue))
        }

        val projectDependencies = Keys.resolvedProjectDependencies.get()
        result.addAll(projectDependencies)

        val unmanaged = Keys.unmanagedDependencies.get()
        result.addAll(unmanaged)

        result
    }

    val InternalClasspath: BoundKeyValue<WList<LocatedFile>> = {
        val compiled = Keys.compile.get()
        val resources = Keys.resourceFiles.get()

        val classpath = WMutableList<LocatedFile>(resources.size + 128)
        constructLocatedFiles(compiled, classpath)
        classpath.addAll(resources)

        classpath
    }

    val Clean: BoundKeyValue<Int> = {
        val folders = arrayOf(
                Keys.outputClassesDirectory.get(),
                Keys.outputSourcesDirectory.get(),
                Keys.outputHeadersDirectory.get()
        )
        var clearedCount = 0
        for (folder in folders) {
            if (folder.exists()) {
                folder.deleteRecursively()
                clearedCount += 1
            }
        }

        for ((_, project) in AllProjects) {
            clearedCount += project.projectScope.cleanCache(true)
        }

        clearedCount
    }

    fun outputClassesDirectory(tag: String): BoundKeyValue<Path> = {
        // Using scopeProject() instead of Keys.projectName, because it has to be unique
        Keys.cacheDirectory.get() / "$tag-${scopeProject().name.toSafeFileName('_')}"
    }

    private val CompileLOG = LoggerFactory.getLogger("Compile")

    private val JavaDiagnosticListener : DiagnosticListener<JavaFileObject> = DiagnosticListener { diagnostic ->
        val source = diagnostic.source
        val location: MessageLocation? =
            if (source == null) {
                null
            } else {
                val lineNumber = diagnostic.lineNumber
                var lineContent:String? = null
                BufferedReader(diagnostic.source.openReader(true)).use {
                    var line = 0L
                    while (true) {
                        val l = it.readLine() ?: break
                        line++
                        if (line == lineNumber) {
                            lineContent = l
                            break
                        }
                    }
                }

                MessageLocation(
                        Paths.get(source.toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS).absolutePath,
                        lineNumber.toInt(),
                        diagnostic.columnNumber.toInt(),
                        lineContent,
                        tabColumnCompensation = 8)
            }

        var lint:String? = null
        // Try to extract lint info from the message
        try {
            // It is sadly not available through public api.
            var jc = diagnostic
            if (jc.javaClass.name == "com.sun.tools.javac.api.ClientCodeWrapper\$DiagnosticSourceUnwrapper") {
                @Suppress("UNCHECKED_CAST")
                jc = jc.javaClass.getDeclaredField("d").get(jc) as Diagnostic<JavaFileObject>
            }
            if (jc.javaClass.name == "com.sun.tools.javac.util.JCDiagnostic") {
                val lintCategory = jc.javaClass.getMethod("getLintCategory").invoke(jc)
                if (lintCategory != null) {
                    lint = lintCategory.javaClass.getField("option").get(lintCategory) as String?
                }
            } else {
                CompileLOG.debug("Failed to extract lint information from {}", diagnostic.javaClass)
            }
        } catch (ex:Exception) {
            CompileLOG.debug("Failed to extract lint information from {}", diagnostic, ex)
        }

        var message = diagnostic.getMessage(Locale.getDefault())
        if (lint != null) {
            // Mimic default format
            message = "[$lint] $message"
        }

        CompileLOG.render(null, diagnostic.kind.name, message, location)
    }

    val CompileJava: BoundKeyValue<Path> = {
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
            output.ensureEmptyDirectory()

            val javaSources = using(compilingJava) { Keys.sourceFiles.get() }
            val javaSourceRoots = mutableSetOf<Path>()
            for ((file, _, root) in javaSources) {
                javaSourceRoots.add((root ?: file).toAbsolutePath())
            }

            val externalClasspath = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry })

            // Compile Java
            if (javaSources.isNotEmpty()) {
                val compiler = using(compilingJava) { Keys.javaCompiler.get() }
                val fileManager = compiler.getStandardFileManager(JavaDiagnosticListener, Locale.getDefault(), StandardCharsets.UTF_8)
                val writerSb = StringBuilder()
                val writer = StringBuilderWriter(writerSb)
                val compilerFlags = using(compilingJava) { Keys.compilerOptions.get() }

                val sourcesOut = using(compilingJava) { Keys.outputSourcesDirectory.get() }
                sourcesOut.ensureEmptyDirectory()
                val headersOut = using(compilingJava) { Keys.outputHeadersDirectory.get() }
                headersOut.ensureEmptyDirectory()

                val pathSeparator = System.getProperty("path.separator", ":")
                val compilerOptions = ArrayList<String>()
                compilerFlags.use(JavaCompilerFlags.customFlags) {
                    compilerOptions.addAll(it)
                }
                compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                    compilerOptions.add("-source")
                    compilerOptions.add(it.version)
                }
                compilerFlags.use(JavaCompilerFlags.targetVersion) {
                    compilerOptions.add("-target")
                    compilerOptions.add(it.version)
                }
                compilerOptions.add("-classpath")
                val classpathString = externalClasspath.joinToString(pathSeparator) { it.absolutePath }
                compilerOptions.add(classpathString)
                compilerOptions.add("-sourcepath")
                compilerOptions.add(javaSourceRoots.joinToString(pathSeparator) { it.absolutePath })
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
                        CompileLOG.info(format, writerSb)
                    } else {
                        CompileLOG.warn(format, writerSb)
                    }
                }

                if (!success) {
                    throw WemiException("Java compilation failed", showStacktrace = false)
                }

                compilerFlags.warnAboutUnusedFlags("Java compiler")
            }

            output
        }
    }

    val CompileJavaKotlin: BoundKeyValue<Path> = {
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
            output.ensureEmptyDirectory()

            val javaSources = using(compilingJava) { Keys.sourceFiles.get() }
            val javaSourceRoots = mutableSetOf<Path>()
            for ((file, _, root) in javaSources) {
                javaSourceRoots.add((root ?: file).toAbsolutePath())
            }
            val kotlinSources = using(compilingKotlin) { Keys.sourceFiles.get() }

            val externalClasspath = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry })

            // Compile Kotlin
            if (kotlinSources.isNotEmpty()) {
                val sources: MutableList<Path> = mutableListOf()
                for ((file, _, _) in kotlinSources) {
                    sources.add(file)
                }
                sources.addAll(javaSourceRoots)

                val compiler = using(compilingKotlin) { Keys.kotlinCompiler.get() }
                val compilerFlags = using(compilingKotlin) { Keys.compilerOptions.get() }

                //TODO Allow to configure cache folder?
                val cacheFolder = output.resolveSibling(output.name + "-kotlin-cache")
                Files.createDirectories(cacheFolder)

                val compileResult = compiler.compileJVM(javaSources + kotlinSources, externalClasspath, output, cacheFolder, compilerFlags, CompileLOG, null)
                if (compileResult != KotlinCompiler.CompileExitStatus.OK) {
                    throw WemiException("Kotlin compilation failed: $compileResult", showStacktrace = false)
                }

                compilerFlags.warnAboutUnusedFlags("Kotlin compiler")
            }

            // Compile Java
            if (javaSources.isNotEmpty()) {
                val compiler = using(compilingJava) { Keys.javaCompiler.get() }
                val fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)
                val writerSb = StringBuilder()
                val writer = StringBuilderWriter(writerSb)
                val compilerFlags = using(compilingJava) { Keys.compilerOptions.get() }

                val sourcesOut = using(compilingJava) { Keys.outputSourcesDirectory.get() }
                sourcesOut.ensureEmptyDirectory()
                val headersOut = using(compilingJava) { Keys.outputHeadersDirectory.get() }
                headersOut.ensureEmptyDirectory()

                val pathSeparator = System.getProperty("path.separator", ":")
                val compilerOptions = ArrayList<String>()
                compilerFlags.use(JavaCompilerFlags.customFlags) {
                    compilerOptions.addAll(it)
                }
                compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                    compilerOptions.add("-source")
                    compilerOptions.add(it.version)
                }
                compilerFlags.use(JavaCompilerFlags.targetVersion) {
                    compilerOptions.add("-target")
                    compilerOptions.add(it.version)
                }
                compilerOptions.add("-classpath")
                val classpathString = externalClasspath.joinToString(pathSeparator) { it.absolutePath }
                if (kotlinSources.isNotEmpty()) {
                    compilerOptions.add(classpathString + pathSeparator + output.absolutePath)
                } else {
                    compilerOptions.add(classpathString)
                }
                compilerOptions.add("-sourcepath")
                compilerOptions.add(javaSourceRoots.joinToString(pathSeparator) { it.absolutePath })
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
                        CompileLOG.info(format, writerSb)
                    } else {
                        CompileLOG.warn(format, writerSb)
                    }
                }

                if (!success) {
                    throw WemiException("Java compilation failed", showStacktrace = false)
                }

                compilerFlags.warnAboutUnusedFlags("Java compiler")
            }

            output
        }
    }

    val RunOptions: BoundKeyValue<WList<String>> = {
        val options = WMutableList<String>()
        options.add("-ea")
        val debugPort = System.getenv("WEMI_RUN_DEBUG_PORT")?.toIntOrNull()
        if (debugPort != null) {
            options.add("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=$debugPort")
        }
        options
    }

    val Run: BoundKeyValue<Int> = {
        using(Configurations.running) {
            val javaExecutable = Keys.javaExecutable.get()
            val classpathEntries = LinkedHashSet<Path>()
            for (locatedFile in Keys.externalClasspath.get()) {
                classpathEntries.add(locatedFile.classpathEntry)
            }
            for (locatedFile in Keys.internalClasspath.get()) {
                classpathEntries.add(locatedFile.classpathEntry)
            }
            val directory = Keys.runDirectory.get()
            val mainClass = Keys.mainClass.get()
            val options = Keys.runOptions.get()
            val arguments = Keys.runArguments.get()

            val processBuilder = wemi.run.prepareJavaProcess(javaExecutable, directory, classpathEntries,
                    mainClass, options, arguments)

            // Separate process output from Wemi output
            println()
            val process = processBuilder.start()
            val result = process.waitFor()
            println()

            result
        }
    }

    val RunMain: BoundKeyValue<Int> = {
        val mainClass = Keys.input.get().read("main", "Main class to start", ClassNameValidator)
                ?: throw WemiException("Main class not specified", showStacktrace = false)

        using({
            Keys.mainClass.set { mainClass }
        }) {
            Keys.run.get()
        }
    }

    val TestParameters: BoundKeyValue<TestParameters> = {
        val testParameters = wemi.test.TestParameters()
        testParameters.filter.classNamePatterns.include("^.*Tests?$")
        testParameters.select.classpathRoots.add(Keys.outputClassesDirectory.get().absolutePath)
        testParameters
    }

    val Test: BoundKeyValue<TestReport> = {
        using(Configurations.testing) {
            val javaExecutable = Keys.javaExecutable.get()
            val directory = Keys.runDirectory.get()
            val options = Keys.runOptions.get()

            val externalClasspath = Keys.externalClasspath.get().map { it.classpathEntry }.distinct()
            val internalClasspath = Keys.internalClasspath.get().map { it.classpathEntry }.distinct()
            val wemiClasspathEntry = WemiBuildScript!!.wemiLauncherJar

            val classpathEntries = ArrayList<Path>(internalClasspath.size + externalClasspath.size + 1)
            classpathEntries.addAll(internalClasspath)
            classpathEntries.addAll(externalClasspath)
            classpathEntries.add(wemiClasspathEntry)

            val processBuilder = wemi.run.prepareJavaProcess(
                    javaExecutable, directory, classpathEntries,
                    TEST_LAUNCHER_MAIN_CLASS, options, emptyList())

            val testParameters = Keys.testParameters.get()

            val report = handleProcessForTesting(processBuilder, testParameters)
                    ?: throw WemiException("Test execution failed, see logs for more information", showStacktrace = false)

            report
        }
    }

    val Archive: BoundKeyValue<Path> = {
        using(archiving) {
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

                outputFile
            }
        }
    }

    /**
     * Special version of [Archive] that includes classpath contributions from [Keys.projectDependencies].
     */
    val ArchivePublishing: BoundKeyValue<Path> = {
        using(archiving) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.internalClasspath.get()) {
                    assemblyOperation.addSource(file, true)
                }

                for (file in Keys.resolvedProjectDependencies.get()) {
                    assemblyOperation.addSource(file, false)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        DefaultAssemblyMapFilter,
                        outputFile,
                        NoPrependData,
                        compress = true)

                outputFile
            }
        }
    }

    val ArchiveSources: BoundKeyValue<Path> = {
        using(archiving) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.sourceFiles.get()) {
                    assemblyOperation.addSource(file, true)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        DefaultAssemblyMapFilter,
                        outputFile,
                        NoPrependData,
                        compress = true)

                outputFile
            }
        }
    }

    /**
     * Binding for [Keys.archive] to use when archiving documentation and no documentation is available.
     */
    val ArchiveDummyDocumentation: BoundKeyValue<Path> = {
        using(Configurations.archiving) {
            AssemblyOperation().use { assemblyOperation ->

                /*
                # No documentation available

                |Group        | Name | Version |
                |:-----------:|:----:|:-------:|
                |com.whatever | Haha | 1.3     |

                *Built by Wemi 1.2*
                *Current date*
                 */

                val groupHeading = "Group"
                val projectGroup = Keys.projectGroup.getOrElse("-")
                val groupWidth = Math.max(groupHeading.length, projectGroup.length) + 2

                val nameHeading = "Name"
                val projectName = Keys.projectName.getOrElse("-")
                val nameWidth = Math.max(nameHeading.length, projectName.length) + 2

                val versionHeading = "Version"
                val projectVersion = Keys.projectVersion.getOrElse("-")
                val versionWidth = Math.max(versionHeading.length, projectVersion.length) + 2

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

                md.append("\n*Built by Wemi ").append(WemiVersion).append("*\n")
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

                outputFile
            }
        }
    }

    /**
     * When java is run from "jre" part of the JDK install, "tools.jar" is not in the classpath by default.
     * This can locate the tools.jar in [Keys.javaHome] for explicit loading.
     */
    private fun Scope.jdkToolsJar():Path? {
        val javaHome = Keys.javaHome.get()
        return javaHome.resolve("lib/tools.jar").takeIf { it.exists() }
                ?: (if (javaHome.name == "jre") javaHome.resolve("../lib/tools.jar").takeIf { it.exists() } else null)
    }

    /**
     * Return URL at which Java SE Javadoc is hosted for given [javaVersion].
     */
    private fun javadocUrl(javaVersion:Int?):String {
        if (javaVersion != null && javaVersion <= 5) {
            //These versions don't have API uploaded, so fall back to 1.5
            // (Version 5 is the first one uploaded, but under non-typical URL)
            return "https://docs.oracle.com/javase/1.5.0/docs/api/"
        } else {
            // Default is 9 because that is newest
            return "https://docs.oracle.com/javase/${javaVersion ?: 9}/docs/api/"
        }
    }

    val ArchiveJavadocOptions: BoundKeyValue<WList<String>> = {
        using(archiving) {
            val options = WMutableList<String>()

            val compilerFlags = using(compilingJava) { Keys.compilerOptions.get() }
            var javaVersionString:String? = null
            compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                options.add("-source")
                options.add(it.version)
                javaVersionString = it.version
            }
            if (javaVersionString == null) {
                javaVersionString = compilerFlags[JavaCompilerFlags.targetVersion]?.version
            }

            val javaVersion = parseJavaVersion(javaVersionString)

            options.add("-link")
            options.add(javadocUrl(javaVersion))

            val pathSeparator = System.getProperty("path.separator", ":")
            options.add("-classpath")
            val classpathString = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry }).joinToString(pathSeparator) { it.absolutePath }
            options.add(classpathString)

            options
        }
    }

    private val ARCHIVE_JAVADOC_LOG = LoggerFactory.getLogger("ArchiveJavadoc")
    val ArchiveJavadoc: BoundKeyValue<Path> = {
        using(archiving) {
            val sourceFiles = using(compilingJava){ Keys.sourceFiles.get() }

            if (sourceFiles.isEmpty()) {
                ARCHIVE_JAVADOC_LOG.info("No source files for Javadoc, creating dummy documentation instead")
                return@using ArchiveDummyDocumentation()
            }

            val diagnosticListener:DiagnosticListener<JavaFileObject> = DiagnosticListener { diagnostic ->
                ARCHIVE_JAVADOC_LOG.debug("{}", diagnostic)
            }

            val documentationTool = ToolProvider.getSystemDocumentationTool()!!
            val fileManager = documentationTool.getStandardFileManager(diagnosticListener, Locale.ROOT, Charsets.UTF_8)
            fileManager.setLocation(StandardLocation.SOURCE_PATH, using(Configurations.compilingJava) { Keys.sourceRoots.get() }.map { it.toFile() })
            val javadocOutput = Keys.cacheDirectory.get() / "javadoc-${Keys.projectName.get().toSafeFileName('_')}"
            javadocOutput.ensureEmptyDirectory()
            fileManager.setLocation(DocumentationTool.Location.DOCUMENTATION_OUTPUT, listOf(javadocOutput.toFile()))

            // Try to specify doclet path explicitly
            val toolsJar = jdkToolsJar()

            if (toolsJar != null) {
                fileManager.setLocation(DocumentationTool.Location.DOCLET_PATH, listOf(toolsJar.toFile()))
            }

            val options = Keys.archiveJavadocOptions.get()

            val docTask = documentationTool.getTask(LineReadingWriter { line ->
                ARCHIVE_JAVADOC_LOG.warn("{}", line)
            }, fileManager,
                    diagnosticListener,
                    null,
                    options,
                    fileManager.getJavaFileObjectsFromFiles(sourceFiles.map { it.file.toFile() }))

            docTask.setLocale(Locale.ROOT)
            val result = docTask.call()

            if (!result) {
                throw WemiException("Failed to package javadoc", showStacktrace = false)
            }

            val locatedFiles = ArrayList<LocatedFile>()
            constructLocatedFiles(javadocOutput, locatedFiles)

            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in locatedFiles) {
                    assemblyOperation.addSource(file, true, false)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        DefaultAssemblyMapFilter,
                        outputFile,
                        NoPrependData,
                        compress = true)

                outputFile
            }
        }
    }

    val ArchiveDokkaOptions: BoundKeyValue<DokkaOptions> = {
        val kotlinOptions = using(compilingKotlin) { Keys.compilerOptions.get() }
        val javaOptions = using(compilingJava) { Keys.compilerOptions.get() }

        val options = DokkaOptions()

        for (sourceRoot in Keys.sourceRoots.get()) {
            if (sourceRoot.exists()) {
                options.sourceRoots.add(DokkaOptions.SourceRoot(sourceRoot))
            }
        }

        options.moduleName = kotlinOptions[KotlinCompilerFlags.moduleName] ?: Keys.projectName.get()
        val javaVersion = parseJavaVersion(
                javaOptions[JavaCompilerFlags.sourceVersion]?.version
                        ?: javaOptions[JavaCompilerFlags.targetVersion]?.version
                        ?: kotlinOptions[KotlinJVMCompilerFlags.jvmTarget])
        if (javaVersion != null) {
            options.jdkVersion = javaVersion
        }
        options.externalDocumentationLinks.add(DokkaOptions.ExternalDocumentation(javadocUrl(javaVersion)))

        options.impliedPlatforms.add("JVM")

        options
    }

    private val DokkaFatJar = listOf(Dependency(DependencyId("org.jetbrains.dokka", "dokka-fatjar", "0.9.15", JCenter), WemiBundledLibrariesExclude))

    val ArchiveDokkaInterface: BoundKeyValue<DokkaInterface> = {
        val artifacts = DependencyResolver.resolveArtifacts(DokkaFatJar, emptyList())?.toMutableList()
                ?: throw IllegalStateException("Failed to retrieve kotlin compiler library")

        jdkToolsJar()?.let { artifacts.add(it) }

        ARCHIVE_DOKKA_LOG.trace("Classpath for Dokka: {}", artifacts)

        val implementationClassName = "wemi.documentation.impl.DokkaInterfaceImpl"
        /** Loads artifacts normally. */
        val dependencyClassLoader = URLClassLoader(artifacts.map { it.toUri().toURL() }.toTypedArray(), WemiDefaultClassLoader)
        /** Makes sure that the implementation class is loaded in a class loader that has artifacts available. */
        val forceClassLoader = EnclaveClassLoader(emptyArray(), dependencyClassLoader,
                implementationClassName, // Own entry point
                "org.jetbrains.dokka.") // Force loading all of Dokka in here

        val clazz = Class.forName(implementationClassName, true, forceClassLoader)

        clazz.newInstance() as DokkaInterface
    }

    private val ARCHIVE_DOKKA_LOG = LoggerFactory.getLogger("ArchiveDokka")
    val ArchiveDokka: BoundKeyValue<Path> = {
        using(archiving) {
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

            val locatedFiles = ArrayList<LocatedFile>()
            constructLocatedFiles(dokkaOutput, locatedFiles)

            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in locatedFiles) {
                    assemblyOperation.addSource(file, true, false)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        DefaultAssemblyMapFilter,
                        outputFile,
                        NoPrependData,
                        compress = true)

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
    val PublishModelM2: BoundKeyValue<InfoNode> = {
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
                        dependency.dependencyId.attribute(Repository.M2.Type)?.let {
                            newChild("type", it)
                        }
                        dependency.dependencyId.attribute(Repository.M2.Classifier)?.let {
                            newChild("classifier", it)
                        }
                        dependency.dependencyId.attribute(Repository.M2.Scope)?.let {
                            newChild("scope", it)
                        }
                        newChild("exclusions") {
                            for (exclusion in dependency.exclusions) {
                                if (exclusion in DefaultExclusions) {
                                    continue
                                }
                                // Check if Maven compatible (only group and name is set)
                                val mavenCompatible = exclusion.group != "*"
                                        && exclusion.name != "*"
                                        && exclusion.version == "*"
                                        && exclusion.attributes.isEmpty()

                                if (mavenCompatible) {
                                    newChild("exclusion") {
                                        newChild("artifactId", exclusion.name)
                                        newChild("groupId", exclusion.group)
                                    }
                                } else {
                                    PUBLISH_MODEL_LOG.warn("Exclusion {} on {} is not supported by pom.xml and will be omitted", exclusion, dependency.dependencyId)
                                }
                            }
                        }
                        dependency.dependencyId.attribute(Repository.M2.Optional)?.let {
                            newChild("optional", it)
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
                    if (repository !is Repository.M2) {
                        PUBLISH_MODEL_LOG.warn("Omitting repository {}, only M2 repositories are supported", repository)
                        continue
                    }
                    if (repository.local) {
                        PUBLISH_MODEL_LOG.warn("Omitting repository {}, it is local")
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

    val PublishM2: BoundKeyValue<URI> = {
        using(publishing) {
            val repository = Keys.publishRepository.get()
            val metadata = Keys.publishMetadata.get()
            val artifacts = Keys.publishArtifacts.get()

            repository.publish(metadata, artifacts)
        }
    }

    val Assembly: BoundKeyValue<Path> = {
        using(assembling) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.internalClasspath.get()) {
                    assemblyOperation.addSource(file, true)
                }
                for (file in Keys.externalClasspath.get()) {
                    assemblyOperation.addSource(file, false)
                }

                val outputFile = Keys.assemblyOutputFile.get()
                assemblyOperation.assembly(Keys.assemblyMergeStrategy.get(),
                        Keys.assemblyRenameFunction.get(),
                        Keys.assemblyMapFilter.get(),
                        outputFile,
                        Keys.assemblyPrependData.get(),
                        compress = true)

                outputFile
            }
        }
    }
}