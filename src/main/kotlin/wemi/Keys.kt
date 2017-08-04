package wemi

import wemi.compile.IKotlinCompiler
import wemi.dependency.ProjectDependency
import wemi.dependency.Repository
import java.io.File
import javax.tools.JavaCompiler
import javax.tools.StandardJavaFileManager

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
    val sourceRoots by key<Collection<File>>("Directories which are source roots for the project")
    val sourceExtensions by key<Collection<String>>("Files with these extensions in sourceRoots are considered to be sources (Stored without .)")
    val sourceFiles by key<Map<File, Collection<File>>>("Files to be compiled. Usually derived from sourceRoots. Maps source root -> source files")

    val repositories by key<Collection<Repository>>("Repositories to be used when resolving dependencies")
    val libraryDependencies by key<Collection<ProjectDependency>>("Libraries that the project depends on")
    val classpath by key<Collection<File>>("Classpath of the project", cached = true)

    val clean by key<Boolean>("Clean compile directories and internal cache, returns true if something cleaned, false if already clean")

    val javaHome by key<File>("Java home to use for compilation/running etc.")
    val javaExecutable by key<File>("Java executable, used for running the project")
    val kotlinVersion by key<String>("Kotlin version used for compilation and standard libraries", WemiKotlinVersion)
    val compilerOptions by key<Collection<String>>("Options passed to the compiler")
    val outputClassesDirectory by key<File>("Directory to which compile key outputs classes")
    val outputSourcesDirectory by key<File>("Directory to which compile key outputs sources")
    val outputHeadersDirectory by key<File>("Directory to which compile key outputs headers")
    val kotlinCompiler by key<IKotlinCompiler>("Kotlin compiler", cached = true)
    val javaCompiler by key<JavaCompiler>("Java compiler", cached = true)
    val compile by key<File>("Compile sources and return the result")

    val mainClass by key<String>("Main class of the project")
    val runDirectory by key<File>("Initial working directory of the project launched by 'run'")
    val runOptions by key<Collection<String>>("Options given to 'java' when running the project")
    val runArguments by key<Collection<String>>("Options given to the application when running the project")
    val run by key<Int>("Compile and run the project, return exit code")

}
