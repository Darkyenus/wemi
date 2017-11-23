@file:Suppress("MemberVisibilityCanPrivate")

package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.Configurations.compilingJava
import wemi.Configurations.compilingKotlin
import wemi.assembly.AssemblySource
import wemi.assembly.FileRecognition
import wemi.assembly.MergeStrategy
import wemi.compile.CompilerFlags
import wemi.compile.JavaCompilerFlags
import wemi.compile.JavaVersion
import wemi.compile.KotlinCompiler
import wemi.dependency.*
import wemi.util.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.collections.ArrayList

object KeyDefaults {

    val BuildDirectory: BoundKeyValue<File> = { Keys.projectRoot.get() / "build" }

    val SourceBaseScopeMain: BoundKeyValue<File> = { Keys.projectRoot.get() / "src/main" }
    val SourceBaseScopeTest: BoundKeyValue<File> = { Keys.projectRoot.get() / "src/test" }

    val SourceRootsJavaKotlin: BoundKeyValue<Collection<File>> = {
        val base = Keys.sourceBase.get()
        listOf(base / "kotlin", base / "java")
    }
    val ResourceRoots: BoundKeyValue<Collection<File>> = {
        val base = Keys.sourceBase.get()
        listOf(base / "resources")
    }

    val SourceFiles: BoundKeyValue<Collection<LocatedFile>> = {
        val roots = Keys.sourceRoots.get()
        val extensions = Keys.sourceExtensions.get()
        val result = ArrayList<LocatedFile>()

        for (root in roots) {
            constructLocatedFiles(root, result) { it.nameHasExtension(extensions) }
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

    val Repositories: BoundKeyValue<Collection<Repository>> = {
        DefaultRepositories
    }
    val LibraryDependencies: BoundKeyValue<Collection<ProjectDependency>> = {
        listOf(KotlinStdlib)
    }
    val ResolvedLibraryDependencies: BoundKeyValue<Partial<Map<ProjectId, ResolvedProject>>> = {
        val repositories = Keys.repositoryChain.get()
        val resolved = mutableMapOf<ProjectId, ResolvedProject>()
        val complete = DependencyResolver.resolve(resolved, Keys.libraryDependencies.get(), repositories, Keys.libraryDependencyProjectMapper.get())
        Partial(resolved, complete)
    }
    val ExternalClasspath: BoundKeyValue<Collection<LocatedFile>> = {
        val resolved = Keys.resolvedLibraryDependencies.get()
        if (!resolved.complete) {
            throw WemiException("Failed to resolve all artifacts")
        }
        val unmanaged = Keys.unmanagedDependencies.get()

        val result = mutableListOf<LocatedFile>()
        result.addAll(unmanaged)
        for (entry in resolved.value) {
            result.add(LocatedFile(entry.value.artifact ?: continue))
        }
        result
    }
    val InternalClasspath: BoundKeyValue<Collection<LocatedFile>> = {
        val compiled = Keys.compile.get()
        val resources = Keys.resourceFiles.get()

        val classpath = ArrayList<LocatedFile>(resources.size + 16)
        constructLocatedFiles(compiled, classpath)
        classpath.addAll(resources)

        classpath
    }
    val Classpath: BoundKeyValue<Collection<LocatedFile>> = {
        Keys.externalClasspath.get() + Keys.internalClasspath.get()
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
            clearedCount += project.projectScope.scopeCache.cleanCache()
        }

        clearedCount
    }
    val JavaHome: BoundKeyValue<File> = {wemi.run.JavaHome}
    val JavaExecutable: BoundKeyValue<File> = {wemi.run.javaExecutable(Keys.javaHome.get())}

    fun outputClassesDirectory(tag:String): BoundKeyValue<File> = {
        Keys.buildDirectory.get() / "cache/$tag-${Keys.projectName.get().toSafeFileName()}"
    }

    private val CompileLOG = LoggerFactory.getLogger("Compile")
    val Compile: BoundKeyValue<File> = {
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
            val javaSources = using(compilingJava) { Keys.sourceFiles.get() }
            val javaSourceRoots = mutableSetOf<File>()
            for ((file, _, root) in javaSources) {
                javaSourceRoots.add((root ?: file).absoluteFile)
            }
            val kotlinSources = using(compilingKotlin) { Keys.sourceFiles.get() }

            val externalClasspath = Keys.externalClasspath.get()

            // Compile Kotlin
            if (kotlinSources.isNotEmpty()) {
                val sources:MutableList<File> = mutableListOf()
                for ((file, _, _) in kotlinSources) {
                    sources.add(file)
                }
                sources.addAll(javaSourceRoots)

                val compiler = using(compilingKotlin) { Keys.kotlinCompiler.get() }
                val compilerFlags = using(compilingKotlin) { Keys.compilerOptions.get() }

                val compileResult = compiler.compile(javaSources + kotlinSources, externalClasspath.map { it.file }.toList(), output, compilerFlags, LoggerFactory.getLogger("ProjectCompilation"), null)
                if (compileResult != KotlinCompiler.CompileExitStatus.OK) {
                    throw WemiException("Kotlin compilation failed: "+compileResult, showStacktrace = false)
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

                output.mkdirs()
                val sourcesOut = using(compilingJava) { Keys.outputSourcesDirectory.get() }
                sourcesOut.mkdirs()
                val headersOut = using(compilingJava) { Keys.outputHeadersDirectory.get() }
                headersOut.mkdirs()

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
                val classpathString = externalClasspath.joinToString(pathSeparator) { it.file.absolutePath }
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

                val javaFiles = fileManager.getJavaFileObjectsFromFiles(javaSources.map { it.file })

                val success = compiler.getTask(
                        writer,
                        fileManager,
                        null,
                        compilerOptions,
                        null,
                        javaFiles
                ).call()

                if (!writerSb.isBlank()) {
                    CompileLOG.info("{}", writerSb)
                }

                if (!success) {
                    throw WemiException("Java compilation failed", showStacktrace = false)
                }

                compilerFlags.warnAboutUnusedFlags("Java compiler")
            }

            output
        }
    }
    val RunDirectory: BoundKeyValue<File> = { Keys.projectRoot.get() }
    val RunOptions: BoundKeyValue<Collection<String>> = {
        val options = mutableListOf<String>()
        options.add("-ea")
        val debugPort = System.getenv("WEMI_RUN_DEBUG_PORT")?.toIntOrNull()
        if (debugPort != null) {
            options.add("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=$debugPort")
        }
        options
    }
    val RunArguments: BoundKeyValue<Collection<String>> = { emptyList() }
    val Run: BoundKeyValue<Int> = {
        using(Configurations.running) {
            val javaExecutable = Keys.javaExecutable.get()
            val classpath = Keys.classpath.get()
            val directory = Keys.runDirectory.get()
            val mainClass = Keys.mainClass.get()
            val options = Keys.runOptions.get()
            val arguments = Keys.runArguments.get()

            val modifiedClasspath = classpath.map { it.classpathEntry }.distinct()

            val process = wemi.run.runJava(javaExecutable, directory, modifiedClasspath, mainClass, options, arguments)
            process.waitFor()
        }
    }

    val AssemblyMergeStrategy: BoundKeyValue<(String) -> MergeStrategy> = {
        { name ->
            // Based on https://github.com/sbt/sbt-assembly logic
            if (FileRecognition.isReadme(name) || FileRecognition.isLicenseFile(name)) {
                MergeStrategy.Rename
            } else if (FileRecognition.isSystemJunkFile(name)) {
                MergeStrategy.Discard
            } else if (name.startsWith("META-INF/")) {
                MergeStrategy.SingleOwn
            } else {
                MergeStrategy.Deduplicate
            }
        }
    }
    val AssemblyRenameFunction: BoundKeyValue<(File, String) -> String?> = {
        { root, name ->
            val injectedName = root.nameWithoutExtension
            val extensionSeparator = name.lastIndexOf('.')
            if (extensionSeparator == -1) {
                name + '_' + injectedName
            } else {
                name.substring(0, extensionSeparator) + '_' + injectedName + name.substring(extensionSeparator)
            }
        }
    }
    private val AssemblyLOG = LoggerFactory.getLogger("Assembly")
    val Assembly: BoundKeyValue<File> = {
        val loadedSources = mutableMapOf<String, MutableList<AssemblySource>>()

        fun addSource(locatedFile: LocatedFile, own:Boolean) {
            val file = locatedFile.file
            if (file.extension.equals("jar", ignoreCase = true)) {
                // Add jar entries
                val zip = ZipFile(file, ZipFile.OPEN_READ, StandardCharsets.UTF_8)
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue

                    val path = entry.name
                    loadedSources.getOrPut(path) { mutableListOf() }.add(object : AssemblySource(file, file, entry, own) {

                        override fun load(): ByteArray = zip.getInputStream(entry).readBytes(entry.size.toInt())

                        override val name: String = file.absolutePath + '?' + path
                    })
                }
            } else {
                // Add file entry
                loadedSources.getOrPut(locatedFile.path) { mutableListOf() }.add(object : AssemblySource(locatedFile.root, file, null, own) {

                    override fun load(): ByteArray = file.readBytes()

                    override val name: String = locatedFile.toString()
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
        val assemblySources = mutableMapOf<String, Pair<AssemblySource?, ByteArray>>()
        val mergeStrategyFunction = Keys.assemblyMergeStrategy.get()

        var hasError = false

        // Renaming has to be done later
        val renamedPaths = mutableMapOf<String, MutableList<AssemblySource>>()

        for ((path, dataList) in loadedSources) {
            if (dataList.size == 1) {
                val single = dataList[0]
                if (AssemblyLOG.isTraceEnabled) {
                    AssemblyLOG.trace("Including single item {}", single.name)
                }
                assemblySources[path] = Pair(single, single.data)
                continue
            }

            // Duplicate
            val strategy = mergeStrategyFunction(path)
            when (strategy) {
                MergeStrategy.First -> {
                    val first = dataList.first()
                    if (AssemblyLOG.isDebugEnabled) {
                        AssemblyLOG.debug("Including first item {}", first.name)
                    }
                    assemblySources[path] = Pair(first, first.data)
                }
                MergeStrategy.Last -> {
                    val last = dataList.last()
                    if (AssemblyLOG.isDebugEnabled) {
                        AssemblyLOG.debug("Including last item {}", last.name)
                    }
                    assemblySources[path] = Pair(last, last.data)
                }
                MergeStrategy.SingleOwn -> {
                    var own:AssemblySource? = null
                    for (source in dataList) {
                        if (source.own) {
                            if (own == null) {
                                own = source
                            } else {
                                AssemblyLOG.error("Own file at {} is also duplicated, one is at {} and other at {}", own.name, source.name)
                                hasError = true
                            }
                        }
                    }

                    if (own == null) {
                        if (AssemblyLOG.isDebugEnabled) {
                            AssemblyLOG.debug("Discarding {} because none of the {} variants is own", path, dataList.size)
                            var i = 1
                            for (source in dataList) {
                                AssemblyLOG.debug("\t{}) {}", i, source.name)
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
                        AssemblyLOG.error("\t{}) {}", i, source.name)
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
                            AssemblyLOG.debug("\t{}) {}", i, source.name)
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
                            AssemblyLOG.debug("\t{}) {}", i, source.name)
                            i += 1
                        }
                    }
                }
                MergeStrategy.Deduplicate -> {
                    val data = Array(dataList.size) { i -> dataList[i].data }

                    for (i in 1..data.lastIndex) {
                        if (!data[0].contentEquals(data[i])) {
                            AssemblyLOG.error("Content for path {} given by {} is not the same as the content provided by {}", path, dataList[0].name, dataList[i].name)
                            hasError = true
                        }
                    }

                    assemblySources[path] = Pair(dataList[0], data[0])
                }
                MergeStrategy.Rename -> {
                    renamedPaths.put(path, dataList)
                }
            }
        }

        if (renamedPaths.isNotEmpty()) {
            val renameFunction = Keys.assemblyRenameFunction.get()

            for ((path, dataList) in renamedPaths) {
                if (AssemblyLOG.isDebugEnabled) {
                    AssemblyLOG.debug("Renaming {} items at {}", dataList.size, path)
                }
                var debugIndex = 1

                for (source in dataList) {
                    val root = source.root
                    var renamedPath = renameFunction(source, path)
                    if (renamedPath == null || renamedPath.removePrefix("/").isBlank()) {
                        if (AssemblyLOG.isDebugEnabled) {
                            AssemblyLOG.debug("\t{}) discarding {}", debugIndex, source.name)
                        }
                    } else {
                        if (!renamedPath.startsWith("/")) {
                            renamedPath = "/" + renamedPath
                        }

                        val alreadyPresent = assemblySources.containsKey(renamedPath)

                        if (alreadyPresent) {
                            AssemblyLOG.error("Can't rename {} from {} to {}, this path is already occupied", path, root, renamedPath)
                            hasError = true
                        } else {
                            if (AssemblyLOG.isDebugEnabled) {
                                AssemblyLOG.debug("\t{}) moving {} to {}", debugIndex, source.name, renamedPath)
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

        val outputFile = Keys.buildDirectory.get() / (Keys.projectName.get() + "-" + Keys.projectVersion.get() + "-assembly.jar")

        JarOutputStream(BufferedOutputStream(FileOutputStream(outputFile, false))).use { out ->
            for ((path, value) in assemblySources) {
                val (source, data) = value

                val entry = ZipEntry(path)
                entry.method = ZipEntry.DEFLATED
                entry.size = data.size.toLong()
                if (source != null) {
                    if (source.file != null) {
                        entry.time = source.file.lastModified()
                    }
                    if (source.zipEntry != null) {
                        if (source.zipEntry.time != -1L) {
                            entry.time = source.zipEntry.time
                        }
                    }
                }

                out.putNextEntry(entry)
                out.write(data)
                out.closeEntry()

                if (AssemblyLOG.isDebugEnabled) {
                    if (source != null) {
                        AssemblyLOG.debug("Writing out entry {} ({} bytes) from {}", path, data.size, source.name)
                    } else {
                        AssemblyLOG.debug("Writing out entry {} ({} bytes)", path, data.size)
                    }
                }
            }
            out.finish()
            out.flush()
        }

        outputFile
    }

    fun Project.applyDefaults() {
        Keys.buildDirectory set BuildDirectory
        Keys.sourceBase set SourceBaseScopeMain
        Keys.sourceFiles set SourceFiles
        Keys.resourceRoots set ResourceRoots
        Keys.resourceFiles set ResourceFiles

        Keys.repositories set Repositories
        Keys.repositoryChain set {
            createRepositoryChain(Keys.repositories.get())
        }
        Keys.libraryDependencies set LibraryDependencies
        Keys.resolvedLibraryDependencies set ResolvedLibraryDependencies
        Keys.unmanagedDependencies set { emptyList() }
        Keys.internalClasspath set InternalClasspath
        Keys.externalClasspath set ExternalClasspath
        Keys.classpath set Classpath

        Keys.clean set Clean

        Keys.javaHome set JavaHome
        Keys.javaExecutable set JavaExecutable
        Keys.outputClassesDirectory set outputClassesDirectory("classes")
        Keys.outputSourcesDirectory set outputClassesDirectory("sources")
        Keys.outputHeadersDirectory set outputClassesDirectory("headers")
        Keys.compilerOptions set { CompilerFlags() }
        extend (Configurations.compilingJava) {
            Keys.compilerOptions[JavaCompilerFlags.sourceVersion] = JavaVersion.V1_8
            Keys.compilerOptions[JavaCompilerFlags.targetVersion] = JavaVersion.V1_8
        }
        Keys.compile set Compile

        //Keys.mainClass TODO Detect main class?
        Keys.runDirectory set RunDirectory
        Keys.runOptions set RunOptions
        Keys.runArguments set RunArguments
        Keys.run set Run

        Keys.assemblyMergeStrategy set AssemblyMergeStrategy
        Keys.assemblyRenameFunction set AssemblyRenameFunction
        Keys.assembly set Assembly
    }
}