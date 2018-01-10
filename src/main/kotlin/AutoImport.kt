@file:Suppress("NOTHING_TO_INLINE", "unused")

import wemi.Configurations
import wemi.dependency.DependencyAttribute
import wemi.dependency.Repository
import java.nio.file.Paths
import wemi.KotlinReflect as _KotlinReflect
import wemi.KotlinStdlib as _KotlinStdLib
import wemi.kotlinDependency as _kotlinDependency
import wemi.test.JUnitAPI as _JUnitAPI
import wemi.test.JUnitEngine as _JUnitEngine

/**
 * Types and values that should be visible in build scripts without any explicit imports
 */

// Types
typealias Key<Value> = wemi.Key<Value>
typealias Configuration = wemi.Configuration
typealias Project = wemi.Project
typealias Scope = wemi.Scope
typealias Repository = wemi.dependency.Repository
typealias ProjectId = wemi.dependency.DependencyId
typealias ProjectDependency = wemi.dependency.Dependency
typealias ProjectExclusion = wemi.dependency.DependencyExclusion

typealias Path = java.nio.file.Path

// Core Functions
inline fun project(projectRoot: Path = path("."), noinline initializer: Project.() -> Unit) = wemi.project(projectRoot, initializer)
inline fun <Value> key(description: String, defaultValue: Value, cached: Boolean = false) = wemi.key(description, defaultValue, cached)
inline fun <Value> key(description: String, cached: Boolean = false) = wemi.key<Value>(description, cached)
inline fun configuration(description: String, parent: Configuration? = null, noinline initializer: Configuration.() -> Unit) = wemi.configuration(description, parent, initializer)
inline fun dependency(group: String, name: String, version: String, preferredRepository: Repository? = null, vararg attributes: Pair<DependencyAttribute, String>) = wemi.dependency(group, name, version, preferredRepository, *attributes)
inline fun dependency(groupNameVersion: String, preferredRepository: Repository? = null, vararg attributes: Pair<DependencyAttribute, String>) = wemi.dependency(groupNameVersion, preferredRepository, *attributes)
inline fun repository(name: String, url: String, checksum: Repository.M2.Checksum = Repository.M2.Checksum.SHA1) = wemi.repository(name, url, checksum)

// Helper functions
inline fun Scope.kotlinDependency(name: String) = _kotlinDependency(name)
val Scope.KotlinStdlib
    inline get() = _KotlinStdLib
val Scope.KotlinReflect
    inline get() = _KotlinReflect
val Scope.JUnitAPI
    inline get() = _JUnitAPI
val Scope.JUnitEngine
    inline get() = _JUnitEngine

fun path(path:String):Path = Paths.get(path)

// Configurations
val compiling
    inline get() = Configurations.compiling
val running
    inline get() = Configurations.running
val testing
    inline get() = Configurations.testing

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
val buildScript
    inline get() = wemi.Keys.buildScript

val input
    inline get() = wemi.Keys.input

val sourceBases
    inline get() = wemi.Keys.sourceBases
val sourceRoots
    inline get() = wemi.Keys.sourceRoots
val sourceExtensions
    inline get() = wemi.Keys.sourceExtensions
val sourceFiles
    inline get() = wemi.Keys.sourceFiles
val resourceRoots
    inline get() = wemi.Keys.resourceRoots
val resourceFiles
    inline get() = wemi.Keys.resourceFiles

val repositories
    inline get() = wemi.Keys.repositories
val repositoryChain
    inline get() = wemi.Keys.repositoryChain
val libraryDependencies
    inline get() = wemi.Keys.libraryDependencies
val libraryDependencyProjectMapper
    inline get() = wemi.Keys.libraryDependencyProjectMapper
val resolvedLibraryDependencies
    inline get() = wemi.Keys.resolvedLibraryDependencies
val unmanagedDependencies
    inline get() = wemi.Keys.unmanagedDependencies
val externalClasspath
    inline get() = wemi.Keys.externalClasspath
val internalClasspath
    inline get() = wemi.Keys.internalClasspath
val classpath
    inline get() = wemi.Keys.classpath

val clean
    inline get() = wemi.Keys.clean

val javaHome
    inline get() = wemi.Keys.javaHome
val javaExecutable
    inline get() = wemi.Keys.javaExecutable
val kotlinVersion
    inline get() = wemi.Keys.kotlinVersion
val compilerOptions
    inline get() = wemi.Keys.compilerOptions
val outputClassesDirectory
    inline get() = wemi.Keys.outputClassesDirectory
val outputSourcesDirectory
    inline get() = wemi.Keys.outputSourcesDirectory
val outputHeadersDirectory
    inline get() = wemi.Keys.outputHeadersDirectory
val kotlinCompiler
    inline get() = wemi.Keys.kotlinCompiler
val javaCompiler
    inline get() = wemi.Keys.javaCompiler
val compile
    inline get() = wemi.Keys.compile

val mainClass
    inline get() = wemi.Keys.mainClass
val runDirectory
    inline get() = wemi.Keys.runDirectory
val runOptions
    inline get() = wemi.Keys.runOptions
val runArguments
    inline get() = wemi.Keys.runArguments
val run
    inline get() = wemi.Keys.run
val runMain
    inline get() = wemi.Keys.runMain

val testParameters
    inline get() = wemi.Keys.testParameters
val test
    inline get() = wemi.Keys.test

val assemblyMergeStrategy
    inline get() = wemi.Keys.assemblyMergeStrategy
val assemblyRenameFunction
    inline get() = wemi.Keys.assemblyRenameFunction
val assembly
    inline get() = wemi.Keys.assembly
