package wemi

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
    val SourceExtensionsList = listOf("java", "kt")
    val SourceExtensions: LazyKeyValue<Collection<String>> = { SourceExtensionsList }
    val SourceFiles: LazyKeyValue<Collection<File>> = {
        val directories = Keys.sourceDirectories.get()
        val extensions = Keys.sourceExtensions.get()
        val result = mutableListOf<File>()

        for (sourceDir in directories) {
            sourceDir.walkTopDown().forEach { file ->
                val ext = file.extension
                if (!file.isDirectory && extensions.any { it.equals(ext, ignoreCase = true) }) {
                    result.add(file)
                }
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

    val CompileOutputFile: LazyKeyValue<File> = {
        Keys.buildDirectory.get() / "classes"
    }
    val Compile: LazyKeyValue<File> = {
        val output = Keys.compileOutputFile.get()
        if (KotlinCompiler.compile(Keys.sourceFiles.get().toList(),
                output,
                Keys.classpath.get().toList(),
                Keys.compilerOptions.get().toTypedArray(),
                LoggerFactory.getLogger("ProjectCompilation"),
                null)) {
            output
        } else throw WemiException("Compilation failed", showStacktrace = false)
    }
    val RunDirectory: LazyKeyValue<File> = { Keys.projectRoot.get() }
    val RunOptionsList = listOf("-ea")
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