package wemi.compile

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.util.WemiDefaultClassLoader
import wemi.dependency.*
import wemi.util.ForceClassLoader
import java.io.File

interface KotlinCompiler {
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
                loggerMarker: Marker? = null): CompileExitStatus

    enum class CompileExitStatus {
        OK,
        CANCELLED,
        COMPILATION_ERROR,
        INTERNAL_ERROR
    }
}

enum class KotlinCompilerVersion(val string:String, internal val implementationClassName:String) {
    Version1_1_4("1.1.4", "wemi.compile.impl.KotlinCompilerImpl1_1_4");

    override fun toString(): String = string
}

private val KotlinCompilerImplementationDependenciesByVersion:Map<KotlinCompilerVersion, Collection<ProjectDependency>> = mapOf(
        KotlinCompilerVersion.Version1_1_4 to listOf(
                ProjectDependency(ProjectId("org.jetbrains.kotlin", "kotlin-compiler", "1.1.4"))
        )
)

private val KotlinCompilerCacheByVersion:MutableMap<KotlinCompilerVersion, KotlinCompiler> = HashMap()

fun kotlinCompiler(forVersion:KotlinCompilerVersion):KotlinCompiler {
    synchronized(KotlinCompilerCacheByVersion) {
        var kotlinCompiler = KotlinCompilerCacheByVersion[forVersion]
        if (kotlinCompiler == null) {
            val repositoryChain = createRepositoryChain(DefaultRepositories)
            val artifacts = DependencyResolver.resolveArtifacts(KotlinCompilerImplementationDependenciesByVersion[forVersion]!!, repositoryChain)
                    ?: throw IllegalStateException("Failed to retrieve kotlin compiler library")

            val implementationClassName = forVersion.implementationClassName
            val loader = ForceClassLoader(artifacts.map { file -> file.toURI().toURL() }.toTypedArray(),
                    WemiDefaultClassLoader, implementationClassName)
            val clazz = Class.forName(implementationClassName, true, loader)

            kotlinCompiler = clazz.newInstance() as KotlinCompiler

            KotlinCompilerCacheByVersion[forVersion] = kotlinCompiler
        }
        return kotlinCompiler
    }
}