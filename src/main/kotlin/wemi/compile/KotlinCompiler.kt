package wemi.compile

import com.esotericsoftware.jsonbeans.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.boot.MachineWritable
import wemi.boot.WemiBundledLibrariesExclude
import wemi.dependency.*
import wemi.util.EnclaveClassLoader
import wemi.util.LocatedPath
import wemi.collections.wSetOf
import wemi.util.Magic
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("KotlinCompiler")

/**
 * Interface implemented by a Kotlin compiler bridge.
 */
interface KotlinCompiler {
    /**
     * @param sources kotlin files to be compiled
     * @param destination for generated class files, folder or .jar file (.jar is not supported for incremental compilation)
     * @param cacheFolder folder for arbitrary opaque caching between runs (when null, caching will be forced off)
     * @param classpath for user class files
     * @param flags custom arguments, parsed by kotlin compiler
     */
    fun compileJVM(sources: Collection<LocatedPath>,
                   classpath: Collection<Path>,
                   destination: Path,
                   cacheFolder: Path?,
                   flags: CompilerFlags,
                   logger: Logger = LoggerFactory.getLogger("KotlinCompiler"),
                   loggerMarker: Marker? = null): CompileExitStatus

    /**
     * Exit status of a [KotlinCompiler].
     *
     * Closely mapped to a real Kotlin compiler exit statuses.
     */
    enum class CompileExitStatus {
        OK,
        CANCELLED,
        COMPILATION_ERROR,
        INTERNAL_ERROR
    }
}

/**
 * Supported Kotlin compiler versions.
 */
@Suppress("EnumEntryName")
enum class KotlinCompilerVersion (
        /** Version name. (X.Y.Z) */
        val string: String,
        /** Class that implements [KotlinCompiler] for given version. */
        private val implementationClassName: String,
        /** Dependencies needed to load [implementationClassName]. */
        private val compilerDependency:Collection<Dependency>) : MachineWritable {

    Version1_1_4_3(
            "1.1.4-3",
            "wemi.compile.impl.KotlinCompilerImpl1_1_4_3",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.1.4-3"), WemiBundledLibrariesExclude))
    ),
    Version1_1_61(
            "1.1.61",
            "wemi.compile.impl.KotlinCompilerImpl1_1_61",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.1.61"), WemiBundledLibrariesExclude))
    ),
    Version1_2_20(
            "1.2.20",
            "wemi.compile.impl.KotlinCompilerImpl1_2_20",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.2.20"), WemiBundledLibrariesExclude))
    ),
    Version1_2_21(
            "1.2.21",
            "wemi.compile.impl.KotlinCompilerImpl1_2_21",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.2.21"), WemiBundledLibrariesExclude))
    ),
    ;

    private var compilerCache:KotlinCompiler? = null

    /**
     * Retrieve the compiler instance for this version.
     *
     * First invocation will need to resolve some dependencies, which may take a while.
     */
    fun compilerInstance():KotlinCompiler {
        synchronized(this) {
            var kotlinCompiler = compilerCache
            if (kotlinCompiler == null) {
                val repositoryChain = createRepositoryChain(DefaultRepositories)
                val artifacts = DependencyResolver.resolveArtifacts(compilerDependency, repositoryChain)
                        ?: throw IllegalStateException("Failed to retrieve kotlin compiler library")

                LOG.trace("Classpath for {} compiler: {}", string, artifacts)

                val implementationClassName = implementationClassName
                /** Loads compiler jar into own enclave, with custom Reflection and own versions of all classes.
                 * This is done because different Kotlin compiler versions are not compatible*/
                val compilerClassLoader = EnclaveClassLoader(artifacts.map { it.toUri().toURL() }.toTypedArray(),
                        Magic.WemiDefaultClassLoader, implementationClassName) // Own entry point

                val clazz = Class.forName(implementationClassName, true, compilerClassLoader)

                kotlinCompiler = clazz.newInstance() as KotlinCompiler

                compilerCache = kotlinCompiler
            }
            return kotlinCompiler
        }
    }

    override fun writeMachine(json: Json) {
        json.writeValue(string as Any, String::class.java)
    }

    override fun toString(): String = string
}

/**
 * Valid Kotlin source file extensions.
 */
val KotlinSourceFileExtensions = wSetOf("kt", "kts")

/**
 * General Kotlin compiler flags.
 */
object KotlinCompilerFlags {
    val customFlags = CompilerFlag<Collection<String>>("customFlags", "Custom flags to be parsed by the Kotlin compiler CLI")

    /* Descriptions and most names taken from the Kotlin at https://github.com/JetBrains/kotlin under Apache 2 license */

    val moduleName = CompilerFlag<String>("moduleName", "Kotlin module name")

    val languageVersion = CompilerFlag<String>("languageVersion", "Provide source compatibility with specified language version")
    val apiVersion = CompilerFlag<String>("apiVersion", "Allow to use declarations only from the specified version of bundled libraries")

    val incremental = CompilerFlag<Boolean>("incremental", "Compile incrementally")

    val pluginOptions = CompilerFlag<Collection<String>>("pluginOptions", "Pass an option to a plugin")
    val pluginClasspath = CompilerFlag<Collection<String>>("pluginClasspath", "Load plugins from the given classpath")
}

/**
 * Kotlin compiler flags specific to JVM
 */
object KotlinJVMCompilerFlags {
    val jdkHome = CompilerFlag<String>("jdkHome", "Path to JDK home directory to include into classpath, if differs from default JAVA_HOME")

    val jvmTarget = CompilerFlag<String>("jvmTarget", "Target version of the generated JVM bytecode (1.6, 1.8)")
}
