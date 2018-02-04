@file:Suppress("MemberVisibilityCanBePrivate")

package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.Configurations.assembling
import wemi.Configurations.compilingJava
import wemi.Configurations.compilingKotlin
import wemi.assembly.*
import wemi.boot.WemiBuildScript
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompiler
import wemi.dependency.DependencyId
import wemi.dependency.DependencyResolver
import wemi.dependency.ResolvedDependency
import wemi.dependency.prettyPrint
import wemi.test.TEST_LAUNCHER_MAIN_CLASS
import wemi.test.TestParameters
import wemi.test.TestReport
import wemi.test.handleProcessForTesting
import wemi.util.*
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
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

    val ResolvedLibraryDependencies: BoundKeyValue<Partial<Map<DependencyId, ResolvedDependency>>> = {
        val repositories = Keys.repositoryChain.get()
        val resolved = mutableMapOf<DependencyId, ResolvedDependency>()
        val complete = DependencyResolver.resolve(resolved, Keys.libraryDependencies.get(), repositories, Keys.libraryDependencyProjectMapper.get())
        Partial(resolved, complete)
    }

    private val ExternalClasspath_LOG = LoggerFactory.getLogger("ProjectDependencyResolution")
    private val ExternalClasspath_CircularDependencyProtection = CycleChecker<Scope>()
    val ExternalClasspath: BoundKeyValue<WList<LocatedFile>> = {
        val result = WMutableList<LocatedFile>()

        val resolved = Keys.resolvedLibraryDependencies.get()
        if (!resolved.complete) {
            throw WemiException("Failed to resolve all artifacts\n${resolved.value.prettyPrint(null)}", showStacktrace = false)
        }
        for ((_, resolvedDependency) in resolved.value) {
            result.add(LocatedFile(resolvedDependency.artifact ?: continue))
        }

        ExternalClasspath_CircularDependencyProtection.block(this, failure = {
            //TODO Show cycle
            throw WemiException("Cyclic dependencies in projectDependencies are not allowed", showStacktrace = false)
        }, action = {
            val projectDependencies = Keys.projectDependencies.get()

            for (projectDependency in projectDependencies) {
                // Enter a different scope
                projectDependency.project.evaluate(*projectDependency.configurations) {
                    ExternalClasspath_LOG.debug("Resolving project dependency on {}", this)
                    result.addAll(Keys.externalClasspath.get())
                    result.addAll(Keys.internalClasspath.get())
                }
            }
        })

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
        Keys.buildDirectory.get() / "cache/$tag-${Keys.projectName.get().toSafeFileName()}"
    }

    private val CompileLOG = LoggerFactory.getLogger("Compile")

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
                        null,
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

                val compileResult = compiler.compileJVM(javaSources + kotlinSources, externalClasspath, output, compilerFlags, CompileLOG, null)
                if (compileResult != KotlinCompiler.CompileExitStatus.OK) {
                    throw WemiException("Kotlin compilation failed: " + compileResult, showStacktrace = false)
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
                        null,
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
                        outputFile,
                        Keys.assemblyPrependData.get(),
                        compress = true)

                outputFile
            }
        }
    }
}