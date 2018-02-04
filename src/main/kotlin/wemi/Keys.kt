package wemi

import wemi.assembly.MergeStrategyChooser
import wemi.assembly.RenameFunction
import wemi.boot.BuildScript
import wemi.compile.CompilerFlags
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompilerVersion
import wemi.dependency.*
import wemi.test.TestParameters
import wemi.test.TestReport
import wemi.test.prettyPrint
import wemi.util.*
import java.nio.file.Path
import javax.tools.JavaCompiler

/**
 * Declarations of all basic Wemi keys.
 */
@Suppress("RemoveExplicitTypeArguments")
object Keys {

    val projectGroup by key<String>("Project group (aka groupId)")
    val projectName by key<String>("Project name (aka artifactId)")
    val projectVersion by key<String>("Project version (aka revision)")

    val startYear by key<Int>("Year of project's inception")

    val projectRoot by key<Path>("Root directory of the project")
    val buildDirectory by key<Path>("Directory in which Wemi stores cache and processed data")
    val buildScript by key<BuildScript>("Build script used to load this project")

    val input by key<Input>("Provides access to user input, that can be programmatically pre-set")

    val sourceBases by key<WSet<Path>>("Directory in which all source directories can be found (example: '/src/main')", defaultValue = wEmptySet())
    val sourceRoots by key<WSet<Path>>("Directories which are source roots for the project (example: '/src/main/java')", defaultValue = wEmptySet())
    val sourceExtensions by key<WSet<String>>("Files with these extensions in sourceRoots are considered to be sources (Stored without .)", defaultValue = wEmptySet())
    val sourceFiles by key<WList<LocatedFile>>("Files to be compiled. Usually derived from sourceRoots and sourceFilter. Maps source root -> source files", defaultValue = wEmptyList())
    val resourceRoots by key<WSet<Path>>("Directories which are resource roots for the project (example: '/src/main/resources')", defaultValue = wEmptySet())
    val resourceFiles by key<WList<LocatedFile>>("Files that are not compiled but are still part of internal classpath. Usually derived from resourceRoots. Maps resource root -> resource files", defaultValue = wEmptyList())

    val repositories by key<WSet<Repository>>("Repositories to be used when resolving dependencies", defaultValue = wEmptySet())
    val repositoryChain by key<RepositoryChain>("ADVANCED - Resolved repository chain from 'repositories'")
    val libraryDependencies by key<WSet<Dependency>>("Libraries that the project depends on", defaultValue = wEmptySet())
    val libraryDependencyProjectMapper by key<(Dependency) -> Dependency>("Function applied to ProjectDependencies encountered while resolving. Used for example when retrieving sources.", defaultValue = { it })
    val resolvedLibraryDependencies by key<Partial<Map<DependencyId, ResolvedDependency>>>("Libraries that the project depends on and were resolved. Resolution may not have been successful.", prettyPrinter = { resolved ->
        resolved.value.prettyPrint(null)
    }, cacheMode = CACHE_ALWAYS)
    val unmanagedDependencies by key<WList<LocatedFile>>("Libraries that should be part of the external classpath but are not managed by project resolvers", defaultValue = wEmptyList())
    val projectDependencies by key<WSet<ProjectDependency>>("Local projects that the project depends on. Project dependency pull in project's internal and external classpath into this project's external classpath", defaultValue = wEmptySet())

    val externalClasspath by key<WList<LocatedFile>>("Classpath of the project, elements from external sources, i.e. library and project dependencies", defaultValue = wEmptyList())
    val internalClasspath by key<WList<LocatedFile>>("Classpath of the project, created internally, i.e. compiled sources and resources", defaultValue = wEmptyList())

    val clean by key<Int>("Clean compile directories and internal cache, returns approximate amount of items cleaned", cacheMode = null)

    val javaHome by key<Path>("Java home to use for compilation/running etc.")
    val javaExecutable by key<Path>("Java executable, used for running the project")
    val kotlinVersion by key<KotlinCompilerVersion>("Kotlin version used for compilation and standard libraries", WemiKotlinVersion)
    val compilerOptions by key<CompilerFlags>("Options passed to the compiler")
    val outputClassesDirectory by key<Path>("Directory to which compile key outputs classes")
    val outputSourcesDirectory by key<Path>("Directory to which compile key outputs sources")
    val outputHeadersDirectory by key<Path>("Directory to which compile key outputs headers")
    val kotlinCompiler by key<KotlinCompiler>("Kotlin compiler", cacheMode = CACHE_ALWAYS)
    val javaCompiler by key<JavaCompiler>("Java compiler", cacheMode = CACHE_ALWAYS)
    val compile by key<Path>("Compile sources and return the result")

    val mainClass by key<String>("Main class of the project")
    val runDirectory by key<Path>("Initial working directory of the project launched by 'run'")
    val runOptions by key<WList<String>>("Options given to 'java' when running the project", defaultValue = wEmptyList())
    val runArguments by key<WList<String>>("Options given to the application when running the project", defaultValue = wEmptyList())
    val run by key<Int>("Compile and run the project, return exit code", cacheMode = null)
    val runMain by key<Int>("Compile and run the project, take the main class from the input (key 'main'), return exit code", cacheMode = null)

    val testParameters by key<TestParameters>("Parameters for the test key. By default discovers all tests in the test sources.")
    val test by key<TestReport>("Run the tests (through the JUnit Platform by default)", prettyPrinter = { it.prettyPrint() })

    val archiveOutputFile by key<Path>("File to which archive should be saved to")
    val archive by key<Path>("Archive project's output and return path to the created file")

    val assemblyMergeStrategy by key<MergeStrategyChooser>("Function for determining which merge strategy should be used when multiple files at the same path are encountered during assembly")
    val assemblyRenameFunction by key<RenameFunction>("Function for renaming assembled duplicate files for which merge strategy is Rename. Paths after rename must not conflict, rules are not recursive.")
    val assemblyPrependData by key<ByteArray>("Data to prepend to the jar created by assembly task", defaultValue = ByteArray(0))
    val assemblyOutputFile by key<Path>("File to which assembled jar should be saved")
    val assembly by key<Path>("Assembly the project and its dependencies into a fat jar")
}
