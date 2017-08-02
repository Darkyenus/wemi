package wemi

import wemi.dependency.ProjectDependency
import wemi.dependency.ProjectId
import wemi.dependency.Repository
import java.io.File

/**
 *
 */
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

    val kotlinVersion by key<String>("Kotlin version used for compilation and standard libraries")
    val compilerOptions by key<Collection<String>>("Options passed to the compiler")
}