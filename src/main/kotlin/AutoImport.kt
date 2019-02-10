@file:Suppress("NOTHING_TO_INLINE", "unused")

import wemi.Archetypes
import wemi.Configurations
import wemi.boot.Task
import wemi.boot.WemiRootFolder
import wemi.boot.autoRunTasks
import java.net.URL
import java.nio.file.Paths
import wemi.kotlinDependency as _kotlinDependency
import wemi.test.JUnitAPI as _JUnitAPI
import wemi.test.JUnitEngine as _JUnitEngine
import wemi.util.div as _div

/*
 * Types and values that should be visible in build scripts without any explicit imports
 */

// Types
typealias Key<Value> = wemi.Key<Value>
typealias Archetype = wemi.Archetype
typealias Configuration = wemi.Configuration
typealias Project = wemi.Project
typealias Scope = wemi.Scope
typealias EvalScope = wemi.EvalScope
typealias Repository = wemi.dependency.Repository
typealias DependencyId = wemi.dependency.DependencyId
typealias Dependency = wemi.dependency.Dependency
typealias DependencyExclusion = wemi.dependency.DependencyExclusion
typealias LocatedPath = wemi.util.LocatedPath

typealias Archetypes = wemi.Archetypes
typealias Configurations = wemi.Configurations
typealias Keys = wemi.Keys

typealias Path = java.nio.file.Path
typealias Paths = java.nio.file.Paths
typealias Files = java.nio.file.Files

val WemiVersion
    get() = wemi.boot.Main.WEMI_VERSION

// Core Functions
inline fun project(vararg archetypes: Archetype = arrayOf(Archetypes.DefaultArchetype), noinline initializer: Project.() -> Unit) = wemi.project(path("."), archetypes = *archetypes, initializer = initializer)
inline fun project(projectRoot: Path? = path("."), vararg archetypes: Archetype = arrayOf(Archetypes.DefaultArchetype), noinline initializer: Project.() -> Unit) = wemi.project(projectRoot, *archetypes, initializer = initializer)

// Helper functions
inline fun EvalScope.kotlinDependency(name: String) = _kotlinDependency(name)
inline val EvalScope.JUnitAPI
    inline get() = _JUnitAPI
val EvalScope.JUnitEngine
    inline get() = _JUnitEngine

@Deprecated("Use ProjectDependency constructor directly (REMOVE IN 0.9)", ReplaceWith("ProjectDependency(project, aggregate, configurations)"))
inline fun dependency(project:Project, aggregate:Boolean, vararg configurations:Configuration) = wemi.dependency.dependency(project, aggregate, *configurations)

@Deprecated("User version with explicit aggregate=true parameter. (REMOVE IN 0.9)", ReplaceWith("dependency(project, true, configurations)"))
inline fun dependency(project:Project, vararg configurations:Configuration) = wemi.dependency.dependency(project, true, *configurations)

// Path helpers
/**
 * Construct a path, from given string, like with [Paths.get].
 * If the resulting path is relative, make it absolute by resolving it on [WemiRootFolder].
 * Resulting path is normalized (though [java.nio.file.Path.normalize]).
 */
fun path(path:String):Path {
    val result = Paths.get(path)
    return if (result.isAbsolute) {
        result.normalize()
    } else {
        WemiRootFolder.resolve(result).normalize()
    }
}
inline operator fun URL.div(path: CharSequence): URL = this._div(path)
inline operator fun Path.div(path: CharSequence): Path = this._div(path)
inline operator fun CharSequence.div(path: CharSequence): StringBuilder = this._div(path)

// Miscellaneous
fun autoRun(task: Task) {
    val tasks = autoRunTasks ?: throw IllegalStateException("Too late to register Task auto-run")
    tasks.add(task)
}

fun Project.autoRun(key:Key<*>, vararg configurations:Configuration) {
    autoRun(Task(this.name, configurations.map { it.name }, key.name, emptyArray()))
}

// Configurations
val compiling
    inline get() = Configurations.compiling
val running
    inline get() = Configurations.running
val assembling
    inline get() = Configurations.assembling
val compilingJava
    inline get() = Configurations.compilingJava
val compilingKotlin
    inline get() = Configurations.compilingKotlin
val testing
    inline get() = Configurations.testing
val archiving
    inline get() = Configurations.archiving
val archivingDocs
    inline get() = Configurations.archivingDocs
val archivingSources
    inline get() = Configurations.archivingSources
val publishing
    inline get() = Configurations.publishing

// Keys
val projectGroup
    inline get() = wemi.Keys.projectGroup
val projectName
    inline get() = wemi.Keys.projectName
val projectVersion
    inline get() = wemi.Keys.projectVersion

val projectRoot
    inline get() = wemi.Keys.projectRoot
val buildDirectory
    inline get() = wemi.Keys.buildDirectory

val sources
    inline get() = wemi.Keys.sources
val resources
    inline get() = wemi.Keys.resources

val repositories
    inline get() = wemi.Keys.repositories
val libraryDependencies
    inline get() = wemi.Keys.libraryDependencies
val libraryDependencyProjectMapper
    inline get() = wemi.Keys.libraryDependencyProjectMapper
val resolvedLibraryDependencies
    inline get() = wemi.Keys.resolvedLibraryDependencies
val unmanagedDependencies
    inline get() = wemi.Keys.unmanagedDependencies
val projectDependencies
    inline get() = wemi.Keys.projectDependencies

val externalClasspath
    inline get() = wemi.Keys.externalClasspath
val internalClasspath
    inline get() = wemi.Keys.internalClasspath

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

val archiveOutputFile
    inline get() = wemi.Keys.archiveOutputFile
val archiveJavadocOptions
    inline get() = wemi.Keys.archiveJavadocOptions
val archiveDokkaOptions
    inline get() = wemi.Keys.archiveDokkaOptions
val archiveDokkaInterface
    inline get() = wemi.Keys.archiveDokkaInterface
val archive
    inline get() = wemi.Keys.archive

val publishMetadata
    inline get() = wemi.Keys.publishMetadata
val publishRepository
    inline get() = wemi.Keys.publishRepository
val publishArtifacts
    inline get() = wemi.Keys.publishArtifacts
val publish
    inline get() = wemi.Keys.publish

val assemblyMergeStrategy
    inline get() = wemi.Keys.assemblyMergeStrategy
val assemblyRenameFunction
    inline get() = wemi.Keys.assemblyRenameFunction
val assemblyMapFilter
    inline get() = wemi.Keys.assemblyMapFilter
val assemblyPrependData
    inline get() = wemi.Keys.assemblyPrependData
val assemblyOutputFile
    inline get() = wemi.Keys.assemblyOutputFile
val assembly
    inline get() = wemi.Keys.assembly

// Build script directive annotations
typealias BuildDependency = wemi.boot.BuildDependency
typealias BuildDependencyRepository = wemi.boot.BuildDependencyRepository
typealias BuildClasspathDependency = wemi.boot.BuildClasspathDependency