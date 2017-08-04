@file:Suppress("NOTHING_TO_INLINE", "unused")

import wemi.dependency.ProjectAttribute
import wemi.dependency.Repository
import wemi.kotlinDependency as _kotlinDependency

/**
 * Types and values that should be visible in build scripts without any explicit imports
 */

// Types
typealias Key<Value> = wemi.Key<Value>
typealias Configuration = wemi.Configuration
typealias Project = wemi.Project
typealias Scope = wemi.Scope
typealias Repository = wemi.dependency.Repository
typealias ProjectId = wemi.dependency.ProjectId
typealias ProjectDependency = wemi.dependency.ProjectDependency
typealias ProjectExclusion = wemi.dependency.ProjectExclusion

typealias File = java.io.File

// Functions
inline fun project(projectRoot: File = File("."), noinline initializer: Project.() -> Unit) = wemi.project(projectRoot, initializer)
inline fun <Value>key(description: String, defaultValue: Value, cached:Boolean = false) = wemi.key(description, defaultValue, cached)
inline fun <Value>key(description: String, cached:Boolean = false) = wemi.key<Value>(description, cached)
inline fun configuration(description: String, parent: Configuration? = null, noinline initializer: Configuration.() -> Unit) = wemi.configuration(description, parent, initializer)
inline fun dependency(group:String, name:String, version:String, preferredRepository: Repository? = null, vararg attributes:Pair<ProjectAttribute, String>) = wemi.dependency(group, name, version, preferredRepository, *attributes)
inline fun dependency(groupNameVersion:String, preferredRepository: Repository? = null, vararg attributes:Pair<ProjectAttribute, String>) = wemi.dependency(groupNameVersion, preferredRepository, *attributes)
inline fun Scope.kotlinDependency(name: String) = _kotlinDependency(name)
inline fun repository(name: String, url:String, checksum: Repository.M2.Checksum = Repository.M2.Checksum.SHA1) = wemi.repository(name, url, checksum)

// Keys
val projectGroup
        inline get() = wemi.Keys.projectGroup
val projectName
        inline get() = wemi.Keys.projectName
val projectVersion
        inline get() = wemi.Keys.projectVersion

val startYear
        inline get() = wemi.Keys.startYear

val projectRoot
        inline get() = wemi.Keys.projectRoot
val buildDirectory
        inline get() = wemi.Keys.buildDirectory
val sourceDirectories
        inline get() = wemi.Keys.sourceRoots
val sourceExtensions
        inline get() = wemi.Keys.sourceExtensions
val sourceFiles
        inline get() = wemi.Keys.sourceFiles

val repositories
        inline get() = wemi.Keys.repositories
val libraryDependencies
        inline get() = wemi.Keys.libraryDependencies
val classpath
        inline get() = wemi.Keys.classpath

val kotlinVersion
        inline get() = wemi.Keys.kotlinVersion
val compilerOptions
        inline get() = wemi.Keys.compilerOptions

val compileOutputFile
        inline get() = wemi.Keys.outputClassesDirectory
val compile
        inline get() = wemi.Keys.compile
val mainClass
        inline get() = wemi.Keys.mainClass
val run
        inline get() = wemi.Keys.run
