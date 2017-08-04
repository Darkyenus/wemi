package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.dependency.*
import wemi.util.div
import wemi.Configurations.javaCompilation
import wemi.Configurations.kotlinCompilation
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*

object KeyDefaults {

    val BuildDirectory: LazyKeyValue<File> = { Keys.projectRoot.get() / "build" }
    val SourceDirectories: LazyKeyValue<Collection<File>> = {
        val root = Keys.projectRoot.get()
        listOf(root / "src/main/kotlin", root / "src/main/java")
    }

    val SourceExtensionsJavaList = listOf("java")
    val SourceExtensionsKotlinList = listOf("kt", "kts")

    val SourceFiles: LazyKeyValue<Map<File, Collection<File>>> = {
        val roots = Keys.sourceRoots.get()
        val extensions = Keys.sourceExtensions.get()
        val result = mutableMapOf<File, ArrayList<File>>()

        for (root in roots) {
            val files = ArrayList<File>()
            root.walkTopDown().forEach { file ->
                val ext = file.extension
                if (!file.isDirectory && extensions.any { it.equals(ext, ignoreCase = true) }) {
                    files.add(file)
                }
            }
            if (files.isNotEmpty()) {
                result.put(root, files)
            }
        }

        result
    }
    val Repositories: LazyKeyValue<Collection<Repository>> = {
        DefaultRepositories
    }
    val LibraryDependencies: LazyKeyValue<Collection<ProjectDependency>> = {
        listOf(kotlinDependency("stdlib"))
    }
    val Classpath: LazyKeyValue<Collection<File>> = {
        val repositories = createRepositoryChain(Keys.repositories.get())
        val artifacts = DependencyResolver.resolveArtifacts(Keys.libraryDependencies.get(), repositories)
        artifacts ?: throw WemiException("Failed to resolve all artifacts")
    }
    val Clean:LazyKeyValue<Boolean> = {
        val folders = arrayOf(
                Keys.outputClassesDirectory.get(),
                Keys.outputSourcesDirectory.get(),
                Keys.outputHeadersDirectory.get()
        )
        var somethingDeleted = false
        for (folder in folders) {
            if (folder.exists()) {
                folder.deleteRecursively()
                somethingDeleted = true
            }
        }

        for ((_, project) in AllProjects) {
            if (project.scopeCache.cleanCache()) {
                somethingDeleted = true
            }
        }

        somethingDeleted
    }
    val JavaHome: LazyKeyValue<File> = {wemi.run.JavaHome}
    val JavaExecutable: LazyKeyValue<File> = {wemi.run.javaExecutable(Keys.javaHome.get())}
    val KotlinCompilerOptionsList = listOf("-no-stdlib")
    val JavaCompilerOptionsList = listOf("-g")

    val OutputClassesDirectory: LazyKeyValue<File> = {
        Keys.buildDirectory.get() / "classes"
    }
    val OutputSourcesDirectory: LazyKeyValue<File> = {
        Keys.buildDirectory.get() / "sources"
    }
    val OutputHeadersDirectory: LazyKeyValue<File> = {
        Keys.buildDirectory.get() / "headers"
    }

    private val LOG = LoggerFactory.getLogger("Compile")
    val Compile: LazyKeyValue<File> = {
        val output = Keys.outputClassesDirectory.get()
        val javaSources = with (javaCompilation) { Keys.sourceFiles.get() }
        val kotlinSources = with (kotlinCompilation) { Keys.sourceFiles.get() }

        val classpath = Keys.classpath.get()

        // Compile Kotlin
        if (kotlinSources.isNotEmpty()) {
            val sources:MutableList<File> = mutableListOf()
            for ((_, files) in kotlinSources) {
                sources.addAll(files)
            }
            for ((root, _) in javaSources) {
                sources.add(root)
            }

            val compiler = with (kotlinCompilation) { Keys.kotlinCompiler.get() }
            val options = with (kotlinCompilation) { Keys.compilerOptions.get() }

            if (!compiler.compile(
                    sources,
                    output,
                    classpath.toList(),
                    options.toTypedArray(),
                    LoggerFactory.getLogger("ProjectCompilation"),
                    null)) {
                throw WemiException("Kotlin compilation failed", showStacktrace = false)
            }
        }

        // Compile Java
        if (javaSources.isNotEmpty()) {
            val compiler = with(javaCompilation) { Keys.javaCompiler.get() }
            val fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)
            val writerSb = StringBuilder()
            val writer = StringBuilderWriter(writerSb)
            val options = with (javaCompilation) { Keys.compilerOptions.get() }

            output.mkdirs()
            val sourcesOut = with (javaCompilation) { Keys.outputSourcesDirectory.get() }
            sourcesOut.mkdirs()
            val headersOut = with (javaCompilation) { Keys.outputHeadersDirectory.get() }
            headersOut.mkdirs()

            val pathSeparator = System.getProperty("path.separator", ":")
            val compilerOptions = options.toMutableList()
            compilerOptions.add("-classpath")
            val classpathString = classpath.joinToString(pathSeparator) { it.absolutePath }
            if (kotlinSources.isNotEmpty()) {
                compilerOptions.add(classpathString + pathSeparator + output.absolutePath)
            } else {
                compilerOptions.add(classpathString)
            }
            compilerOptions.add("-sourcepath")
            compilerOptions.add(javaSources.keys.joinToString(pathSeparator) { it.absolutePath })
            compilerOptions.add("-d")
            compilerOptions.add(output.absolutePath)
            compilerOptions.add("-s")
            compilerOptions.add(sourcesOut.absolutePath)
            compilerOptions.add("-h")
            compilerOptions.add(headersOut.absolutePath)

            val javaFiles = fileManager.getJavaFileObjectsFromFiles(javaSources.values.flatten())

            val success = compiler.getTask(
                    writer,
                    fileManager,
                    null,
                    compilerOptions,
                    null,
                    javaFiles
            ).call()

            if (!writerSb.isNullOrBlank()) {
                LOG.info("{}", writerSb)
            }

            if (!success) {
                throw WemiException("Java compilation failed", showStacktrace = false)
            }
        }

        output
    }
    val RunDirectory: LazyKeyValue<File> = { Keys.projectRoot.get() }
    val RunOptionsList = listOf("-ea", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
    val RunOptions: LazyKeyValue<Collection<String>> = { RunOptionsList }
    val RunArguments: LazyKeyValue<Collection<String>> = { emptyList() }
    val Run: LazyKeyValue<Int> = {
        val javaExecutable = Keys.javaExecutable.get()
        val compileOutput = Keys.compile.get()
        val classpath = Keys.classpath.get()
        val directory = Keys.runDirectory.get()
        val mainClass = Keys.mainClass.get()
        val options = Keys.runOptions.get()
        val arguments = Keys.runArguments.get()
        val process = wemi.run.runJava(javaExecutable, directory, classpath + compileOutput, mainClass, options, arguments)
        process.waitFor()
    }

    fun Project.applyDefaults() {
        Keys.buildDirectory set BuildDirectory
        Keys.sourceRoots set SourceDirectories
        Keys.sourceFiles set SourceFiles
        Keys.repositories set Repositories
        Keys.libraryDependencies set LibraryDependencies
        Keys.classpath set Classpath

        Keys.clean set Clean

        Keys.javaHome set JavaHome
        Keys.javaExecutable set JavaExecutable
        Keys.outputClassesDirectory set OutputClassesDirectory
        Keys.outputSourcesDirectory set OutputSourcesDirectory
        Keys.outputHeadersDirectory set OutputHeadersDirectory
        Keys.compile set Compile

        //Keys.mainClass TODO Detect main class?
        Keys.runDirectory set RunDirectory
        Keys.runOptions set RunOptions
        Keys.runArguments set RunArguments
        Keys.run set Run
    }
}