package wemi

import wemi.dependency.ProjectDependency
import wemi.dependency.Repository
import java.io.File

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
    val sourceDirectories by key<Collection<File>>("Directories which are source roots for the project")
    val sourceExtensions by key<Collection<String>>("Files with these extensions in sourceDirectories are considered to be sources (Stored without .)")
    val sourceFiles by key<Collection<File>>("Files to be compiled. Usually derived from sourceDirectories")

    val repositories by key<Collection<Repository>>("Repositories to be used when resolving dependencies")
    val libraryDependencies by key<Collection<ProjectDependency>>("Libraries that the project depends on")
    val classpath by key<Collection<File>>("Classpath of the project")

    val javaHome by key<File>("Java home to use for compilation/running etc.")
    val javaExecutable by key<File>("Java executable, used for running the project")
    val kotlinVersion by key<String>("Kotlin version used for compilation and standard libraries", WemiKotlinVersion)
    val compilerOptions by key<Collection<String>>("Options passed to the compiler")
    val compileOutputFile by key<File>("File or directory to which compile key compiles")
    val compile by key<File>("Compile sources and return the result")

    val mainClass by key<String>("Main class of the project")
    val runDirectory by key<File>("Initial working directory of the project launched by 'run'")
    val runOptions by key<Collection<String>>("Options given to 'java' when running the project")
    val runArguments by key<Collection<String>>("Options given to the application when running the project")
    val run by key<Process>("Compile and run the project")
}
