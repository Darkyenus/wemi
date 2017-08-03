package wemi

import configuration
import org.slf4j.LoggerFactory
import wemi.compile.KotlinCompiler
import wemi.dependency.*
import wemi.util.div
import java.io.File

object KeyDefaults {
    val BuildDirectory: LazyKeyValue<File> = { Keys.projectRoot.get() / "build" }
    val SourceDirectories: LazyKeyValue<Collection<File>> = {
        val root = Keys.projectRoot.get()
        listOf(root / "src/main/kotlin", root / "src/main/java")
    }

    val SourceExtensionsJavaList = listOf("java")
    val javaSources by configuration("Configuration used when collecting Java sources") {
        Keys.sourceExtensions set { SourceExtensionsJavaList }
    }

    val SourceExtensionsKotlinList = listOf("kt", "kts")
    val kotlinSources by configuration("Configuration used when collecting Kotlin sources") {
        Keys.sourceExtensions set { SourceExtensionsKotlinList }
    }

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
    val JavaHome: LazyKeyValue<File> = {wemi.run.JavaHome}
    val JavaExecutable: LazyKeyValue<File> = {wemi.run.javaExecutable(Keys.javaHome.get())}
    val CompilerOptionsList = listOf("-no-stdlib")
    val CompilerOptions: LazyKeyValue<Collection<String>> = {
        CompilerOptionsList
    }

    val CompileOutputDirectory: LazyKeyValue<File> = {
        Keys.buildDirectory.get() / "classes"
    }
    val Compile: LazyKeyValue<File> = {
        val output = Keys.compileOutputDirectory.get()
        val javaSources = with (javaSources) { Keys.sourceFiles.get() }
        val kotlinSources = with (kotlinSources) { Keys.sourceFiles.get() }

        // Compile Kotlin
        if (kotlinSources.isNotEmpty()) {
            val sources:MutableList<File> = mutableListOf()
            for ((_, files) in kotlinSources) {
                sources.addAll(files)
            }
            for ((root, _) in javaSources) {
                sources.add(root)
            }

            if (!KotlinCompiler.compile(
                    sources,
                    output,
                    Keys.classpath.get().toList(),
                    Keys.compilerOptions.get().toTypedArray(),
                    LoggerFactory.getLogger("ProjectCompilation"),
                    null)) {
                throw WemiException("Compilation failed", showStacktrace = false)
            }
        }

        // Compile Java
        if (javaSources.isNotEmpty()) {
            // TODO
        }

        output
    }
    val RunDirectory: LazyKeyValue<File> = { Keys.projectRoot.get() }
    val RunOptionsList = listOf("-ea", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
    val RunOptions: LazyKeyValue<Collection<String>> = { RunOptionsList }
    val RunArguments: LazyKeyValue<Collection<String>> = { emptyList() }
    val Run: LazyKeyValue<Process> = {
        val javaExecutable = Keys.javaExecutable.get()
        val compileOutput = Keys.compile.get()
        val classpath = Keys.classpath.get()
        val directory = Keys.runDirectory.get()
        val mainClass = Keys.mainClass.get()
        val options = Keys.runOptions.get()
        val arguments = Keys.runArguments.get()
        wemi.run.runJava(javaExecutable, directory, classpath + compileOutput, mainClass, options, arguments)
    }
}