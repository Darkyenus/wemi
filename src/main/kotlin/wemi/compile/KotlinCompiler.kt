package wemi.compile

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.dependency.*
import wemi.util.ForceClassLoader
import wemi.util.LocatedFile
import wemi.util.WemiDefaultClassLoader
import java.io.File

interface KotlinCompiler {
    /**
     * @param sources kotlin files to be compiled
     * @param destination for generated class files, folder or .jar file
     * @param classpath for user class files
     * @param flags custom arguments, parsed by kotlin compiler
     */
    fun compile(sources: List<LocatedFile>,
                classpath: List<File>,
                destination: File,
                flags: CompilerFlags,
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

val KotlinSourceFileExtensions = listOf("kt", "kts")

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
}
