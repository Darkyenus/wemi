@file:Suppress("RemoveExplicitTypeArguments")

package wemi.compile

import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.ActivityListener
import wemi.WemiException
import wemi.boot.WemiBundledLibrariesExclude
import wemi.dependency.*
import wemi.util.*
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
                   loggerMarker: Marker? = null): CompileExitStatus {
        return compile(CompilationType.JVM, sources, classpath, destination, cacheFolder, flags, logger, loggerMarker)
    }

    /**
     * @param sources kotlin files to be compiled
     * @param destination for generated class files, folder or .jar file (.jar is not supported for incremental compilation)
     * @param cacheFolder folder for arbitrary opaque caching between runs (when null, caching will be forced off)
     * @param classpath for user class files
     * @param flags custom arguments, parsed by kotlin compiler
     */
    fun compile(type:CompilationType,
                sources: Collection<LocatedPath>,
                classpath: Collection<Path>,
                destination: Path,
                cacheFolder: Path?,
                flags: CompilerFlags,
                logger: Logger = LoggerFactory.getLogger("KotlinCompiler"),
                loggerMarker: Marker? = null): CompileExitStatus {
        if (type == CompilationType.JVM) {
            return compileJVM(sources, classpath, destination, cacheFolder, flags, logger, loggerMarker)
        }
        throw WemiException("This Kotlinc version does not support this compilation type")
    }

    enum class CompilationType {
        JVM,
        JS
    }

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
        private val compilerDependency:Collection<Dependency>) : JsonWritable {

    Version1_1_61(
            "1.1.61",
            "wemi.compile.impl.KotlinCompilerImpl1_1_61",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.1.61"), exclusions = WemiBundledLibrariesExclude))
    ),
    Version1_2_71(
            "1.2.71",
            "wemi.compile.impl.KotlinCompilerImpl1_2_71",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.2.71"), exclusions = WemiBundledLibrariesExclude))
    ),
    Version1_3_20(
            "1.3.20",
            "wemi.compile.impl.KotlinCompilerImpl1_3_20",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.3.20"), exclusions = WemiBundledLibrariesExclude))
    ),
    Version1_3_41(
            "1.3.41",
            "wemi.compile.impl.KotlinCompilerImpl1_3_41",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.3.41"), exclusions = WemiBundledLibrariesExclude))
    ),
    Version1_3_61(
            "1.3.61",
            "wemi.compile.impl.KotlinCompilerImpl1_3_61",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.3.61"), exclusions = WemiBundledLibrariesExclude))
    ),
    Version1_3_72(
            "1.3.72",
            "wemi.compile.impl.KotlinCompilerImpl1_3_72",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.3.72"), exclusions = WemiBundledLibrariesExclude))
    ),
    Version1_4_10(
            "1.4.10",
            "wemi.compile.impl.KotlinCompilerImpl1_4_10",
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.4.10"), exclusions = WemiBundledLibrariesExclude))
    ),
    ;

    private var compilerCache:KotlinCompiler? = null

    /**
     * Retrieve the compiler instance for this version.
     *
     * First invocation will need to resolve some dependencies, which may take a while.
     */
    fun compilerInstance(progressTracker:ActivityListener?):KotlinCompiler {
        synchronized(this) {
            var kotlinCompiler = compilerCache
            if (kotlinCompiler == null) {
                val artifacts = resolveDependencyArtifacts(compilerDependency, listOf(MavenCentral), progressTracker)
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

    override fun JsonWriter.write() {
        writeValue(string, String::class.java)
    }

    override fun toString(): String = string
}

/** Valid Kotlin source file extensions */
val KotlinSourceFileExtensions = arrayOf("kt", "kts")

/** General Kotlin compiler flags. */
object KotlinCompilerFlags {
    val customFlags = CompilerFlag<List<String>>("kotlincCustomFlags", "Custom flags to be parsed by the Kotlin compiler CLI", emptyList())

    /* Descriptions and most names taken from the Kotlin at https://github.com/JetBrains/kotlin under Apache 2 license */

    val moduleName = CompilerFlag<String>("kotlincModuleName", "Kotlin module name", "")

    val languageVersion = CompilerFlag<String>("kotlincLanguageVersion", "Provide source compatibility with specified language version", "")
    val apiVersion = CompilerFlag<String>("kotlincApiVersion", "Allow to use declarations only from the specified version of bundled libraries", "")

    val incremental = CompilerFlag<Boolean>("kotlincIncremental", "Compile incrementally", false)

    val pluginOptions = CompilerFlag<List<String>>("kotlincPluginOptions", "Pass an option to a plugin", emptyList())
    val pluginClasspath = CompilerFlag<List<String>>("kotlincPluginClasspath", "Load plugins from the given classpath", emptyList())
}

/** Kotlin compiler flags specific to JVM */
object KotlinJVMCompilerFlags {
    val jdkHome = CompilerFlag<String>("kotlincJdkHome", "Path to JDK home directory to include into classpath, if differs from default JAVA_HOME", "")

    val jvmTarget = CompilerFlag<String>("kotlincJvmTarget", "Target version of the generated JVM bytecode (1.6, 1.8)", "1.6")
}

object KotlinJSCompilerFlags {
    val sourceMap = CompilerFlag<Boolean>("sourceMap", "Generate source map", true)
    val sourceMapPrefix = CompilerFlag<String?>("sourceMapPrefix", "Add the specified prefix to paths in the source map", null)

    const val SOURCE_MAP_EMBED_ALWAYS = "always"
    const val SOURCE_MAP_EMBED_NEVER = "never"
    const val SOURCE_MAP_EMBED_INLINING = "inlining"
    val sourceMapEmbedSources = CompilerFlag<String?>("sourceMapEmbedSources", "Embed source files into source map", SOURCE_MAP_EMBED_ALWAYS)

    val metaInfo = CompilerFlag<Boolean>("metaInfo", "Generate .meta.js and .kjsm files with metadata. Use to create a library", false)
    const val TARGET_V5 = "v5"
    val target = CompilerFlag<String?>("target", "Generate JS files for specific ECMA version", TARGET_V5)

    const val MODULE_KIND_PLAIN = "plain"
    const val MODULE_KIND_AMD = "amd"
    const val MODULE_KIND_COMMONJS = "commonjs"
    const val MODULE_KIND_UMD = "umd"
    val moduleKind = CompilerFlag<String?>("moduleKind", "Kind of the JS module generated by the compiler", MODULE_KIND_PLAIN)

    const val MAIN_CALL = "call"
    const val MAIN_NO_CALL = "noCall"
    val main = CompilerFlag<String?>("main", "Define whether the `main` function should be called upon execution", MAIN_NO_CALL)

    val outputPrefix = CompilerFlag<Path?>("outputPrefix", "Add the content of the specified file to the beginning of output file", null)
    val outputPostfix = CompilerFlag<Path?>("outputPostfix", "Add the content of the specified file to the end of output file", null)
}
