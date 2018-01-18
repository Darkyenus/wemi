@file:Suppress("MemberVisibilityCanBePrivate")

package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.Configurations.assembling
import wemi.Configurations.compilingJava
import wemi.Configurations.compilingKotlin
import wemi.assembly.AssemblySource
import wemi.assembly.FileRecognition
import wemi.assembly.MergeStrategy
import wemi.boot.WemiBuildScript
import wemi.boot.WemiRunningInInteractiveMode
import wemi.compile.CompilerFlags
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompiler
import wemi.dependency.*
import wemi.run.javaExecutable
import wemi.test.TEST_LAUNCHER_MAIN_CLASS
import wemi.test.TestParameters
import wemi.test.TestReport
import wemi.test.handleProcessForTesting
import wemi.util.*
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.collections.LinkedHashSet

/**
 * Contains default values bound to keys.
 *
 * This includes implementations of most tasks.
 */
object KeyDefaults {

    val BuildDirectory: BoundKeyValue<Path> = { Keys.projectRoot.get() / "build" }

    val SourceBase: BoundKeyValue<Collection<Path>> = {
        listOf(Keys.projectRoot.get() / "src/main")
    }

    val SourceRootsJavaKotlin: BoundKeyValue<Collection<Path>> = {
        val bases = Keys.sourceBases.get()
        val roots = ArrayList<Path>()
        for (base in bases) {
            roots.add(base / "kotlin")
            roots.add(base / "java")
        }
        roots
    }

    val ResourceRoots: BoundKeyValue<Collection<Path>> = {
        val bases = Keys.sourceBases.get()
        val roots = ArrayList<Path>()
        for (base in bases) {
            roots.add(base / "resources")
        }
        roots
    }

    val SourceFiles: BoundKeyValue<Collection<LocatedFile>> = {
        val roots = Keys.sourceRoots.get()
        val extensions = Keys.sourceExtensions.get()
        val result = ArrayList<LocatedFile>()

        for (root in roots) {
            constructLocatedFiles(root, result) { it.name.pathHasExtension(extensions) }
        }

        result
    }

    val ResourceFiles: BoundKeyValue<Collection<LocatedFile>> = {
        val roots = Keys.resourceRoots.get()
        val result = ArrayList<LocatedFile>()

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
    val ExternalClasspath: BoundKeyValue<Collection<LocatedFile>> = {
        val result = ArrayList<LocatedFile>()

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
                projectDependency.project.projectScope.run {
                    using(*projectDependency.configurations) {
                        ExternalClasspath_LOG.debug("Resolving project dependency on {}", this)
                        result.addAll(Keys.externalClasspath.get())
                        result.addAll(Keys.internalClasspath.get())
                    }
                }
            }
        })

        val unmanaged = Keys.unmanagedDependencies.get()
        result.addAll(unmanaged)

        result
    }

    val InternalClasspath: BoundKeyValue<Collection<LocatedFile>> = {
        val compiled = Keys.compile.get()
        val resources = Keys.resourceFiles.get()

        val classpath = ArrayList<LocatedFile>(resources.size + 128)
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
            clearedCount += project.projectScope.cleanCache()
        }

        clearedCount
    }

    fun outputClassesDirectory(tag: String): BoundKeyValue<Path> = {
        Keys.buildDirectory.get() / "cache/$tag-${Keys.projectName.get().toSafeFileName()}"
    }

    private val CompileLOG = LoggerFactory.getLogger("Compile")
    val Compile: BoundKeyValue<Path> = {
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
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

                val compileResult = compiler.compile(javaSources + kotlinSources, externalClasspath, output, compilerFlags, CompileLOG, null)
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

                Files.createDirectories(output)
                val sourcesOut = using(compilingJava) { Keys.outputSourcesDirectory.get() }
                Files.createDirectories(sourcesOut)
                val headersOut = using(compilingJava) { Keys.outputHeadersDirectory.get() }
                Files.createDirectories(headersOut)

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

    val RunOptions: BoundKeyValue<Collection<String>> = {
        val options = mutableListOf<String>()
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
            val wemiClasspathEntry = wemiLauncherFileWithJarExtension(WemiBuildScript!!.cacheFolder)

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


    val AssemblyMergeStrategy: BoundKeyValue<(String) -> MergeStrategy> = {
        { name ->
            // Based on https://github.com/sbt/sbt-assembly logic
            if (FileRecognition.isReadme(name) || FileRecognition.isLicenseFile(name)) {
                MergeStrategy.Rename
            } else if (FileRecognition.isSystemJunkFile(name)) {
                MergeStrategy.Discard
            } else if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
                MergeStrategy.Rename
            } else if (name.startsWith("META-INF/services/", ignoreCase = true)) {
                MergeStrategy.Concatenate
            } else if (name.startsWith("META-INF/", ignoreCase = true)) {
                MergeStrategy.SingleOwn
            } else {
                MergeStrategy.Deduplicate
            }
        }
    }

    val AssemblyRenameFunction: BoundKeyValue<(AssemblySource, String) -> String?> = {
        { root, name ->
            if (root.own) {
                name
            } else {
                val injectedName = root.file.name.pathWithoutExtension()
                val extensionSeparator = name.lastIndexOf('.')
                if (extensionSeparator == -1) {
                    name + '_' + injectedName
                } else {
                    name.substring(0, extensionSeparator) + '_' + injectedName + name.substring(extensionSeparator)
                }
            }
        }
    }

    private fun normalizeZipPath(path:String):String {
        return path.replace('\\', '/').removePrefix("/")
    }

    private val AssemblyLOG = LoggerFactory.getLogger("Assembly")
    val Assembly: BoundKeyValue<Path> = {
        using(assembling) {
            val loadedSources = LinkedHashMap<String, ArrayList<AssemblySource>>()

            fun addSource(locatedFile: LocatedFile, own: Boolean) {
                val file = locatedFile.file
                if (file.name.pathHasExtension("jar")) {
                    // Add jar entries
                    val zip = ZipFile(file.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8)
                    //TODO CLOSE THE FILE!!!

                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue

                        val path = normalizeZipPath(entry.name)
                        loadedSources.getOrPut(path) { ArrayList() }.add(object : AssemblySource(file, file, entry, own) {

                            override fun load(): ByteArray = zip.getInputStream(entry).use { it.readBytes(entry.size.toInt()) }

                            override val debugName: String = file.absolutePath + '?' + path
                        })
                    }
                } else {
                    // Add file entry
                    loadedSources.getOrPut(normalizeZipPath(locatedFile.path)) { ArrayList() }.add(object : AssemblySource(locatedFile.root, file, null, own) {

                        override fun load(): ByteArray = Files.readAllBytes(file)

                        override val debugName: String = locatedFile.toString()
                    })
                }
            }

            // Load data
            for (file in Keys.internalClasspath.get()) {
                addSource(file, true)
            }
            for (file in Keys.externalClasspath.get()) {
                addSource(file, false)
            }

            // Trim duplicates
            val assemblySources = LinkedHashMap<String, Pair<AssemblySource?, ByteArray>>()
            val mergeStrategyFunction = Keys.assemblyMergeStrategy.get()

            var hasError = false

            // Renaming has to be done later, because it is not yet known which paths are clean for renaming
            val sourcesToBeRenamed = LinkedHashMap<String, MutableList<AssemblySource>>()

            for ((path, dataList) in loadedSources) {
                if (dataList.size == 1) {
                    val single = dataList[0]
                    if (AssemblyLOG.isTraceEnabled) {
                        AssemblyLOG.trace("Including single item {}", single.debugName)
                    }
                    assemblySources[path] = Pair(single, single.data)
                    continue
                }

                // Resolve duplicate
                val strategy = mergeStrategyFunction(path)
                when (strategy) {
                    MergeStrategy.First -> {
                        val first = dataList.first()
                        if (AssemblyLOG.isDebugEnabled) {
                            AssemblyLOG.debug("Including first item {}", first.debugName)
                        }
                        assemblySources[path] = Pair(first, first.data)
                    }
                    MergeStrategy.Last -> {
                        val last = dataList.last()
                        if (AssemblyLOG.isDebugEnabled) {
                            AssemblyLOG.debug("Including last item {}", last.debugName)
                        }
                        assemblySources[path] = Pair(last, last.data)
                    }
                    MergeStrategy.SingleOwn -> {
                        var own: AssemblySource? = null
                        for (source in dataList) {
                            if (source.own) {
                                if (own == null) {
                                    own = source
                                } else {
                                    AssemblyLOG.error("Own file at {} is also duplicated, one is at {} and other at {}", own.debugName, source.debugName)
                                    hasError = true
                                }
                            }
                        }

                        if (own == null) {
                            if (AssemblyLOG.isDebugEnabled) {
                                AssemblyLOG.debug("Discarding {} because none of the {} variants is own", path, dataList.size)
                                var i = 1
                                for (source in dataList) {
                                    AssemblyLOG.debug("\t{}) {}", i, source.debugName)
                                    i += 1
                                }
                            }
                        } else {
                            if (AssemblyLOG.isDebugEnabled) {
                                AssemblyLOG.debug("Using own version of {} out of {} candidates", path, dataList.size)
                            }
                            assemblySources[path] = Pair(own, own.data)
                        }
                    }
                    MergeStrategy.SingleOrError -> {
                        AssemblyLOG.error("File at {} has {} candidates, which is illegal under SingleOrError merge strategy", path, dataList.size)
                        var i = 1
                        for (source in dataList) {
                            AssemblyLOG.error("\t{}) {}", i, source.debugName)
                            i += 1
                        }
                        hasError = true
                    }
                    MergeStrategy.Concatenate -> {
                        var totalLength = 0
                        val data = Array(dataList.size) { i ->
                            val loaded = dataList[i].data
                            totalLength += loaded.size
                            loaded
                        }

                        val concatenated = ByteArray(totalLength)
                        var pointer = 0
                        for (d in data) {
                            System.arraycopy(d, 0, concatenated, pointer, d.size)
                            pointer += d.size
                        }

                        if (AssemblyLOG.isDebugEnabled) {
                            AssemblyLOG.debug("Including {} concatenated items ({} bytes total)", dataList.size, totalLength)
                            var i = 1
                            for (source in dataList) {
                                AssemblyLOG.debug("\t{}) {}", i, source.debugName)
                                i += 1
                            }
                        }

                        assemblySources[path] = Pair(null, concatenated)
                    }
                    MergeStrategy.Discard -> {
                        if (AssemblyLOG.isDebugEnabled) {
                            AssemblyLOG.debug("Discarding {} items", dataList.size)
                            var i = 1
                            for (source in dataList) {
                                AssemblyLOG.debug("\t{}) {}", i, source.debugName)
                                i += 1
                            }
                        }
                    }
                    MergeStrategy.Deduplicate -> {
                        val data = Array(dataList.size) { i -> dataList[i].data }

                        for (i in 1..data.lastIndex) {
                            if (!data[0].contentEquals(data[i])) {
                                AssemblyLOG.error("Content for path {} given by {} is not the same as the content provided by {}", path, dataList[0].debugName, dataList[i].debugName)
                                hasError = true
                            }
                        }

                        assemblySources[path] = Pair(dataList[0], data[0])
                    }
                    MergeStrategy.Rename -> {
                        sourcesToBeRenamed[path] = dataList
                    }
                }
            }

            // Resolve those that should be renamed
            if (sourcesToBeRenamed.isNotEmpty()) {
                val renameFunction = Keys.assemblyRenameFunction.get()

                for ((path, dataList) in sourcesToBeRenamed) {
                    if (AssemblyLOG.isDebugEnabled) {
                        AssemblyLOG.debug("Renaming {} items at {}", dataList.size, path)
                    }
                    var debugIndex = 1

                    for (source in dataList) {
                        val root = source.root
                        val renamedPath = normalizeZipPath(renameFunction(source, path) ?: "")
                        if (renamedPath.isEmpty()) {
                            if (AssemblyLOG.isDebugEnabled) {
                                AssemblyLOG.debug("\t{}) discarding {}", debugIndex, source.debugName)
                            }
                        } else {
                            val alreadyPresent = assemblySources.containsKey(renamedPath)

                            if (alreadyPresent) {
                                AssemblyLOG.error("Can't rename {} from {} to {}, this path is already occupied", path, root, renamedPath)
                                hasError = true
                            } else {
                                if (AssemblyLOG.isDebugEnabled) {
                                    AssemblyLOG.debug("\t({}) moving {} to {}", debugIndex, source.debugName, renamedPath)
                                }

                                assemblySources[renamedPath] = Pair(source, source.data)
                            }
                        }
                        debugIndex += 1
                    }
                }
            }

            if (hasError) {
                throw WemiException("assembly task failed", showStacktrace = false)
            }

            val outputFile = Keys.assemblyOutputFile.get()

            val prependData = Keys.assemblyPrependData.get()

            BufferedOutputStream(Files.newOutputStream(outputFile)).use { out ->

                if (prependData.isNotEmpty()) {
                    out.write(prependData)
                }

                val jarOut = JarOutputStream(out)

                for ((path, value) in assemblySources) {
                    val (source, data) = value

                    val entry = ZipEntry(path)
                    entry.method = ZipEntry.DEFLATED
                    entry.size = data.size.toLong()
                    if (source != null) {
                        if (source.zipEntry != null && source.zipEntry.time != -1L) {
                            entry.time = source.zipEntry.time
                        } else {
                            entry.time = source.file.lastModified.toMillis()
                        }
                    }

                    jarOut.putNextEntry(entry)
                    jarOut.write(data)
                    jarOut.closeEntry()

                    if (AssemblyLOG.isDebugEnabled) {
                        if (source != null) {
                            AssemblyLOG.debug("Writing out entry {} ({} bytes) from {}", path, data.size, source.debugName)
                        } else {
                            AssemblyLOG.debug("Writing out entry {} ({} bytes)", path, data.size)
                        }
                    }
                }

                jarOut.finish()
                jarOut.flush()
                jarOut.close()

                AssemblyLOG.debug("{} entries written", assemblySources.size)
            }

            outputFile
        }
    }

    fun Project.applyDefaults() {
        Keys.input set { InputBase(WemiRunningInInteractiveMode) }

        Keys.buildDirectory set BuildDirectory
        Keys.sourceBases set SourceBase
        Keys.sourceFiles set SourceFiles
        Keys.resourceRoots set ResourceRoots
        Keys.resourceFiles set ResourceFiles

        Keys.repositories set { DefaultRepositories }
        Keys.repositoryChain set { createRepositoryChain(Keys.repositories.get()) }
        Keys.libraryDependencies set { listOf(kotlinDependency("stdlib")) }
        Keys.resolvedLibraryDependencies set ResolvedLibraryDependencies
        Keys.internalClasspath set InternalClasspath
        Keys.externalClasspath set ExternalClasspath

        Keys.clean set Clean

        Keys.javaHome set { wemi.run.JavaHome }
        Keys.javaExecutable set { javaExecutable(Keys.javaHome.get()) }
        Keys.outputClassesDirectory set outputClassesDirectory("classes")
        Keys.outputSourcesDirectory set outputClassesDirectory("sources")
        Keys.outputHeadersDirectory set outputClassesDirectory("headers")
        Keys.compilerOptions set { CompilerFlags() }
        Keys.compile set Compile

        //Keys.mainClass TODO Detect main class?
        Keys.runDirectory set { Keys.projectRoot.get() }
        Keys.runOptions set RunOptions
        Keys.run set Run
        Keys.runMain set RunMain

        Keys.testParameters set TestParameters
        Keys.test set Test

        Keys.assemblyMergeStrategy set AssemblyMergeStrategy
        Keys.assemblyRenameFunction set AssemblyRenameFunction
        Keys.assemblyOutputFile set { Keys.buildDirectory.get() / (Keys.projectName.get() + "-" + Keys.projectVersion.get() + "-assembly.jar") }
        Keys.assembly set Assembly
    }
}