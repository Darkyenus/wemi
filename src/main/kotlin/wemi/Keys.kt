package wemi

import wemi.assembly.AssemblyMapFilter
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.MergeStrategyChooser
import wemi.assembly.RenameFunction
import wemi.compile.CompilerFlags
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompilerVersion
import wemi.dependency.*
import wemi.documentation.DokkaInterface
import wemi.documentation.DokkaOptions
import wemi.publish.ArtifactEntry
import wemi.publish.InfoNode
import wemi.run.EnvVarMachineReadableFormatter
import wemi.run.ProcessBuilderMachineReadableFormatter
import wemi.run.ProcessBuilderPrettyPrinter
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

    /**
     * See http://central.sonatype.org/pages/choosing-your-coordinates.html for information
     * about how to determine correct groupId.
     */
    val projectGroup by key<String>("Project group (a.k.a. groupId)")
    val projectName by key<String>("Project name (aka artifactId)")
    val projectVersion by key<String>("Project version (aka revision)")

    val projectRoot by key<Path>("Root directory of the project")
    val buildDirectory by key<Path>("Directory with Wemi build scripts, directories with logs, cache, etc.")
    val cacheDirectory by key<Path>("Directory in which Wemi stores cache and processed data")

    /**
     * Contains all source files. Individual compilers process them based on their extension.
     * Under [wemi.Configurations.testing] contains test sources as well (in addition to normal sources).
     */
    val sources by key<FileSet?>("Source files of the project (compiled, part of internal classpath)", defaultValue = null, prettyPrinter = FILE_SET_PRETTY_PRINTER)
    val resources by key<FileSet?>("Resource files of the project (not compiled, part of internal classpath)", defaultValue = null, prettyPrinter = FILE_SET_PRETTY_PRINTER)
    val testSources by key<FileSet?>("Extra source files of the project for testing (compiled, part of internal classpath)", defaultValue = null, prettyPrinter = FILE_SET_PRETTY_PRINTER)
    val testResources by key<FileSet?>("Extra resource files of the project for testing (not compiled, part of internal classpath)", defaultValue = null, prettyPrinter = FILE_SET_PRETTY_PRINTER)

    val repositories by key<Set<Repository>>("Repositories to be used when resolving dependencies", defaultValue = emptySet())
    val libraryDependencies by key<Set<Dependency>>("Libraries that the project depends on", defaultValue = emptySet())
    val libraryDependencyMapper by key<(Dependency) -> Dependency>("Function applied to transitive Dependency-ies encountered while resolving. Used for example when retrieving sources.", defaultValue = { it })
    val resolvedLibraryDependencies by key<Partial<ResolvedDependencies>>("Libraries that the project depends on and were resolved. Resolution may not have been successful.", prettyPrinter = { resolved, _ ->
        resolved.value.prettyPrint()
    })
    val unmanagedDependencies by key<List<LocatedPath>>("Libraries that should be part of the external classpath but are not managed by project resolvers", defaultValue = emptyList())
    val projectDependencies by key<Set<ProjectDependency>>("Local projects that the project depends on. Project dependency pull in project's internal and external classpath into this project's external classpath", defaultValue = emptySet())

    val automaticKotlinStdlib by key<Boolean>("Automatically add Kotlin stdlib for Kotlin projects", defaultValue = true)

    /** @see wemi.generation.generateClasspath */
    val generatedClasspath by key<List<LocatedPath>>("Eventually of internalClasspath, but generated before compilation", defaultValue = emptyList())

    val externalClasspath by key<List<ScopedLocatedPath>>("Classpath, externally obtained elements from external sources, i.e. library dependencies, external classpath of all project dependencies and internal classpath of non-aggregate project dependencies", defaultValue = emptyList())
    val internalClasspath by key<List<LocatedPath>>("Classpath, internally created elements, i.e. compiled sources and resources, including those of aggregate project dependencies", defaultValue = emptyList())

    val scopesCompile by key<Set<DepScope>>("Which dependency scopes should be used for compilation classpath", setOf(ScopeCompile, ScopeProvided, ScopeAggregate))
    val scopesRun by key<Set<DepScope>>("Which dependency scopes should be used for runtime classpath", setOf(ScopeCompile, ScopeRuntime, ScopeAggregate))
    val scopesTest by key<Set<DepScope>>("Which dependency scopes should be used for testing classpath", setOf(ScopeCompile, ScopeRuntime, ScopeProvided, ScopeTest, ScopeAggregate))

    val javaHome by key<JavaHome>("Java home and executable to use for compilation/running etc.")
    val kotlinVersion by key<KotlinCompilerVersion>("Kotlin version used for compilation and standard libraries", WemiKotlinVersion)
    val compilerOptions by key<CompilerFlags>("Options passed to the compiler")
    // These directories must stay here to be accessible from IDE
    val outputClassesDirectory by key<Path>("Directory for compiler created classes")
    val outputSourcesDirectory by key<Path>("Directory for compiler created sources")
    val outputHeadersDirectory by key<Path>("Directory for compiler created headers")
    val outputJavascriptDirectory by key<Path>("Directory for compiler created JavaScript files")
    val kotlinCompiler by key<KotlinCompiler>("Kotlin compiler")
    val javaCompiler by key<JavaCompiler>("Java compiler")
    val compile by key<Path>("Compile sources and return the result")

    val mainClass by key<String>("Main class of the project")
    val runDirectory by key<Path>("Initial working directory of the project launched by 'run'")
    val runOptions by key<List<String>>("Options given to 'java' when running the project", defaultValue = emptyList())
    val runSystemProperties by key<Map<String, String>>("System properties given to 'java' when running the project", defaultValue = emptyMap())
    val runArguments by key<List<String>>("Options given to the application when running the project", defaultValue = emptyList())
    val runEnvironment by key<Map<String, String>>("Environment variables used when running the project", defaultValue = emptyMap(), machineReadableFormatter = EnvVarMachineReadableFormatter)
    val runProcess by key<ProcessBuilder>("Prepared process for running", prettyPrinter = ProcessBuilderPrettyPrinter, machineReadableFormatter = ProcessBuilderMachineReadableFormatter)
    val run by key<Int>("Compile and run the project, return exit code")

    val testParameters by key<TestParameters>("Parameters for the test key. By default, discovers all tests in the test sources", inputKeys = arrayOf("class" to "Include classes, whose fully classified name match this regex"))
    val test by key<TestReport>("Run the tests (through the JUnit Platform by default)", prettyPrinter = { it, _ -> it.prettyPrint() })

    val archiveJavadocOptions by key<List<String>>("Options when archiving Javadoc")
    val archiveDokkaOptions by key<DokkaOptions>("Options when archiving Dokka")
    val archiveDokkaInterface by key<DokkaInterface>("Dokka instance used when creating documentation")
    val archive by key<Path?>("Archive project's output and return path to the created file, if any")
    val archiveSources by key<Path>("Archive project's sources and return path to the created file")
    val archiveDocs by key<Path>("Archive project's documentation and return path to the created file")

    val publishMetadata by key<InfoNode>("Meta-information that should be published together with archives by 'publish'")
    val publishRepository by key<Repository>("Repository to which the archives are published")
    /** @see wemi.publish.artifacts preferred method for adding to this list. */
    val publishArtifacts by key<List<ArtifactEntry>>("Artifacts that should get published", defaultValue = emptyList())
    val publish by key<Path>("Publish archives to 'publishRepository' and return the URI to where it was published")

    val externalSources by key<List<Path>>("Sources of the external classpath, useful for IDE integration", emptyList())
    val externalDocs by key<List<Path>>("Documentation for the external classpath, useful for IDE integration", emptyList())

    val assemblyMergeStrategy by key<MergeStrategyChooser>("Function for determining which merge strategy should be used when multiple files at the same path are encountered during assembly")
    val assemblyRenameFunction by key<RenameFunction>("Function for renaming assembled duplicate files for which merge strategy is Rename. Paths after rename must not conflict, rules are not recursive.")
    val assemblyMapFilter by key<AssemblyMapFilter>("Function that allows controlling what gets into the resulting archive on a fine-grained level.", defaultValue = DefaultAssemblyMapFilter)
    val assemblyPrependData by key<ByteArray>("Data to prepend to the jar created by assembly task", defaultValue = ByteArray(0))
    val assemblyOutputFile by key<Path>("File to which assembled jar should be saved")
    val assembly by key<Path>("Assembly the project and its dependencies into a fat jar")
}
