package wemi.compile

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.WemiKotlinVersion
import wemi.util.WemiDefaultClassLoader
import wemi.dependency.*
import wemi.util.ForceClassLoader
import java.io.File

internal interface IKotlinCompiler {
    /**
     * @param sources kotlin files to be compiled
     * @param destination for generated class files, folder or .jar file
     * @param classpath for user class files
     * @param arguments custom arguments, parsed by kotlin compiler
     */
    fun compile(sources: List<File>,
                destination: File,
                classpath: List<File>,
                arguments: Array<String>,
                logger: Logger = LoggerFactory.getLogger("KotlinCompiler"),
                loggerMarker: Marker? = null): Boolean
}

private val KotlinCompilerImplementationDependencies = listOf(
        ProjectDependency(ProjectId("org.jetbrains.kotlin", "kotlin-compiler", WemiKotlinVersion))
)

internal val KotlinCompiler: IKotlinCompiler by lazy {
    val repositoryChain = createRepositoryChain(DefaultRepositories)
    val artifacts = DependencyResolver.resolveArtifacts(KotlinCompilerImplementationDependencies, repositoryChain)
            ?: throw IllegalStateException("Failed to retrieve kotlin compiler library")

    val implementationClassName = "wemi.compile.impl.KotlinCompilerImpl"
    val loader = ForceClassLoader(artifacts.map { file -> file.toURI().toURL() }.toTypedArray(),
            WemiDefaultClassLoader, implementationClassName)
    val clazz = Class.forName(implementationClassName, true, loader)

    clazz.newInstance() as IKotlinCompiler
}