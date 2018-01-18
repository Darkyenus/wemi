package wemi.compile

import com.esotericsoftware.jsonbeans.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.boot.MachineWritable
import wemi.dependency.*
import wemi.util.ForceClassLoader
import wemi.util.LocatedFile
import wemi.util.WemiDefaultClassLoader
import java.nio.file.Path

/**
 * Interface implemented by a Kotlin compiler bridge.
 */
interface KotlinCompiler {
    /**
     * @param sources kotlin files to be compiled
     * @param destination for generated class files, folder or .jar file
     * @param classpath for user class files
     * @param flags custom arguments, parsed by kotlin compiler
     */
    fun compile(sources: Collection<LocatedFile>,
                classpath: Collection<Path>,
                destination: Path,
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
            listOf(Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-compiler", "1.1.4-3")))
    );

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

                val implementationClassName = implementationClassName
                val loader = ForceClassLoader(artifacts.map { file -> file.toUri().toURL() }.toTypedArray(),
                        WemiDefaultClassLoader, implementationClassName)
                val clazz = Class.forName(implementationClassName, true, loader)

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
val KotlinSourceFileExtensions = listOf("kt", "kts")

/**
 * General Kotlin compiler flags.
 */
object KotlinCompilerFlags {
    /* Descriptions and most names taken from the Kotlin at https://github.com/JetBrains/kotlin under Apache 2 license */

    //TODO Stronger type
    val languageVersion = CompilerFlag<String>("languageVersion", "Provide source compatibility with specified language version")
    //TODO Stronger type
    val apiVersion = CompilerFlag<String>("apiVersion", "Allow to use declarations only from the specified version of bundled libraries")
    val pluginOptions = CompilerFlag<Array<String>>("pluginOptions", "Pass an option to a plugin")
    val pluginClasspaths = CompilerFlag<Array<String>>("pluginClasspaths", "Load plugins from the given classpath")
    val noInline = CompilerFlag<Boolean>("noInline", "Disable method inlining")
    val skipMetadataVersionCheck = CompilerFlag<Boolean>("skipMetadataVersionCheck", "Load classes with bad metadata version anyway (incl. pre-release classes)")
    val allowKotlinPackage = CompilerFlag<Boolean>("allowKotlinPackage", "Allow compiling code in package 'kotlin'")
    val reportOutputFiles = CompilerFlag<Boolean>("reportOutputFiles", "Report source to output files mapping")
    val multiPlatform = CompilerFlag<FeatureState>("multiPlatform", "Enable experimental language support for multi-platform projects")
    val multiPlatformDoNotCheckImpl = CompilerFlag<Boolean>("multiPlatformDoNotCheckImpl", "Do not check presence of 'impl' modifier in multi-platform projects")
    val intellijPluginRoot = CompilerFlag<String>("intellijPluginRoot", "Path to the kotlin-compiler.jar or directory where IntelliJ configuration files can be found")
    val coroutinesState = CompilerFlag<FeatureState>("coroutinesState", "Enable coroutines or report warnings or errors on declarations and use sites of 'suspend' modifier")

    enum class FeatureState {
        Enabled,
        EnabledWithWarning,
        EnabledWithError,
        Disabled
    }
}

/**
 * Kotlin compiler flags specific to JVM
 */
object KotlinJVMCompilerFlags {
    val includeRuntime = CompilerFlag<Boolean>("includeRuntime", "Include Kotlin runtime in to resulting .jar")
    val jdkHome = CompilerFlag<String>("jdkHome", "Path to JDK home directory to include into classpath, if differs from default JAVA_HOME")
    //TODO probably not needed
    val noJdk = CompilerFlag<Boolean>("noJdk", "Don't include Java runtime into classpath")
    val moduleName = CompilerFlag<String>("moduleName", "Module name")

    enum class BytecodeTarget(val string: String) {
        JAVA_1_6("1.6"),
        JAVA_1_8("1.8")
    }

    val jvmTarget = CompilerFlag<BytecodeTarget>("jvmTarget", "Target version of the generated JVM bytecode")
    val javaParameters = CompilerFlag<Boolean>("javaParameters", "Generate metadata for Java 1.8 reflection on method parameters")

    val javaModulePath = CompilerFlag<String>("javaModulePath", "Paths where to find Java 9+ modules")
    val additionalJavaModules = CompilerFlag<Array<String>>("additionalJavaModules", "Root modules to resolve in addition to the initial modules, or all modules on the module path if <module> is ALL-MODULE-PATH")

    val noCallAssertions = CompilerFlag<Boolean>("noCallAssertions", "Don't generate not-null assertion after each invocation of method returning not-null")
    val noParamAssertions = CompilerFlag<Boolean>("noParamAssertions", "Don't generate not-null assertions on parameters of methods accessible from Java")
    val noOptimize = CompilerFlag<Boolean>("noOptimize", "Disable optimizations")
    //TODO Probably not needed
    val reportPerf = CompilerFlag<Boolean>("reportPerf", "Report detailed performance statistics")
    val inheritMultifileParts = CompilerFlag<Boolean>("inheritMultifileParts", "Compile multifile classes as a hierarchy of parts and facade")
    val skipRuntimeVersionCheck = CompilerFlag<Boolean>("skipRuntimeVersionCheck", "Allow Kotlin runtime libraries of incompatible versions in the classpath")
    val useOldClassFilesReading = CompilerFlag<Boolean>("useOldClassFilesReading", "Use old class files reading implementation (may slow down the build and should be used in case of problems with the new implementation)")
    val declarationsOutputPath = CompilerFlag<String>("declarationsOutputPath", "Path to JSON file to dump Java to Kotlin declaration mappings")
    val singleModule = CompilerFlag<Boolean>("singleModule", "Combine modules for source files and binary dependencies into a single module")
    val addCompilerBuiltIns = CompilerFlag<Boolean>("addCompilerBuiltIns", "Add definitions of built-in declarations to the compilation classpath (useful with -no-stdlib)")
    val loadBuiltInsFromDependencies = CompilerFlag<Boolean>("loadBuiltInsFromDependencies", "Load definitions of built-in declarations from module dependencies, instead of from the compiler")

    val useJavac = CompilerFlag<Boolean>("useJavac", "Use javac for Java source and class files analysis")
    val javacArguments = CompilerFlag<Array<String>>("javacArguments", "Java compiler arguments")

    enum class Jsr305GlobalReportLevel {
        Ignore,
        Warn,
        Enable
    }

    val jsr305GlobalReportLevel = CompilerFlag<Jsr305GlobalReportLevel>("jsr305GlobalReportLevel", "Specify global behavior for JSR-305 nullability annotations: ignore, or treat as other supported nullability annotations")

    //TODO What?
    val friendPaths = CompilerFlag<Array<String>>("friendPaths", "Paths to output directories for friend modules.")

    /** Used to allow compiling of .wemi files by Kotlin compiler */
    val compilingWemiBuildFiles = CompilerFlag<Boolean>("compilingWemiBuildFiles", "Internal flag")
}
