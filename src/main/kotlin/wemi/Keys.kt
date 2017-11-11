package wemi

import wemi.assembly.AssemblySource
import wemi.assembly.MergeStrategy
import wemi.compile.CompilerFlags
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompilerVersion
import wemi.dependency.*
import wemi.util.LocatedFile
import wemi.util.Partial
import java.io.File
import javax.tools.JavaCompiler

/**
 *
 */
@Suppress("RemoveExplicitTypeArguments")
object Keys {

    val projectGroup by key<String>("Project group (aka groupId)")
    val projectName by key<String>("Project name (aka artifactId)")
    val projectVersion by key<String>("Project version (aka revision)")

    val startYear by key<Int>("Year of project's inception")

    val projectRoot by key<File>("Root directory of the project")
    val buildDirectory by key<File>("Directory in which Wemi stores cache and processed data")

    val sourceBase by key<File>("Directory in which all source directories can be found (example: '/src/main')")
    val sourceRoots by key<Collection<File>>("Directories which are source roots for the project (example: '/src/main/java')")
    val sourceExtensions by key<Collection<String>>("Files with these extensions in sourceRoots are considered to be sources (Stored without .)")
    val sourceFiles by key<Collection<LocatedFile>>("Files to be compiled. Usually derived from sourceRoots and sourceFilter. Maps source root -> source files")
    val resourceRoots by key<Collection<File>>("Directories which are resource roots for the project (example: '/src/main/resources')")
    val resourceFiles by key<Collection<LocatedFile>>("Files that are not compiled but are still part of internal classpath. Usually derived from resourceRoots. Maps resource root -> resource files")

    val repositories by key<Collection<Repository>>("Repositories to be used when resolving dependencies")
    val repositoryChain by key<RepositoryChain>("ADVANCED - Resolved repository chain from 'repositories'")
    val libraryDependencies by key<Collection<ProjectDependency>>("Libraries that the project depends on")
    val libraryDependencyProjectMapper by key<(ProjectDependency) -> ProjectDependency>("Function applied to ProjectDependencies encountered while resolving. Used for example when retrieving sources.", defaultValue = {it})
    val resolvedLibraryDependencies by key<Partial<Map<ProjectId, ResolvedProject>>>("Libraries that the project depends on and were resolved. Resolution may not have been successful.")
    val unmanagedDependencies by key<Collection<LocatedFile>>("Libraries that should be part of the external classpath but are not managed by project resolvers")
    val externalClasspath by key<Collection<LocatedFile>>("External classpath of the project, usually dependencies", cached = true)
    val internalClasspath by key<Collection<LocatedFile>>("Internal classpath of the project, usually compiled sources and resources")
    val classpath by key<Collection<LocatedFile>>("Full classpath of the project, combined external and internal classpath")

    val clean by key<Int>("Clean compile directories and internal cache, returns approximate amount of items cleaned")

    val javaHome by key<File>("Java home to use for compilation/running etc.")
    val javaExecutable by key<File>("Java executable, used for running the project")
    val kotlinVersion by key<KotlinCompilerVersion>("Kotlin version used for compilation and standard libraries", WemiKotlinVersion)
    val compilerOptions by key<CompilerFlags>("Options passed to the compiler")
    val outputClassesDirectory by key<File>("Directory to which compile key outputs classes")
    val outputSourcesDirectory by key<File>("Directory to which compile key outputs sources")
    val outputHeadersDirectory by key<File>("Directory to which compile key outputs headers")
    val kotlinCompiler by key<KotlinCompiler>("Kotlin compiler", cached = true)
    val javaCompiler by key<JavaCompiler>("Java compiler", cached = true)
    val compile by key<File>("Compile sources and return the result")

    val mainClass by key<String>("Main class of the project")
    val runDirectory by key<File>("Initial working directory of the project launched by 'run'")
    val runOptions by key<Collection<String>>("Options given to 'java' when running the project")
    val runArguments by key<Collection<String>>("Options given to the application when running the project")
    val run by key<Int>("Compile and run the project, return exit code")

    val assemblyMergeStrategy by key<(String) -> MergeStrategy>("Function for determining which merge strategy should be used when multiple files at the same path are encountered during assembly")
    val assemblyRenameFunction by key<(AssemblySource, String) -> String?>("Function for renaming assembled duplicate files for which merge strategy is Rename. First argument is the source of the data, second is the path inside the root. Returns new path or null to discard. Paths after rename must not conflict, rules are not recursive.")
    val assembly by key<File>("Assembly the project and its dependencies into a fat jar")
}
