@file:Suppress("NOTHING_TO_INLINE", "unused")

import wemi.boot.Task
import wemi.boot.WemiRootFolder
import wemi.boot.autoRunTasks
import wemi.dependency.Classifier
import wemi.dependency.DEFAULT_OPTIONAL
import wemi.dependency.DEFAULT_SCOPE
import wemi.dependency.DEFAULT_SNAPSHOT_VERSION
import wemi.dependency.DEFAULT_TYPE
import wemi.dependency.NoClassifier
import java.net.URL
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
    get() = wemi.boot.WemiVersion

// Core Functions
inline fun project(vararg archetypes: Archetype = arrayOf(Archetypes.DefaultArchetype), noinline initializer: Project.() -> Unit) = wemi.project(path("."), archetypes = *archetypes, initializer = initializer)
inline fun project(projectRoot: Path? = path("."), vararg archetypes: Archetype = arrayOf(Archetypes.DefaultArchetype), noinline initializer: Project.() -> Unit) = wemi.project(projectRoot, *archetypes, initializer = initializer)

// Helper functions
inline fun EvalScope.kotlinDependency(name: String) = _kotlinDependency(name)
inline val JUnitAPI
    inline get() = _JUnitAPI
val JUnitEngine
    inline get() = _JUnitEngine

val ScopeCompile
    inline get() = wemi.dependency.ScopeCompile
val ScopeProvided
    inline get() = wemi.dependency.ScopeProvided
val ScopeRuntime
    inline get() = wemi.dependency.ScopeRuntime
val ScopeTest
    inline get() = wemi.dependency.ScopeTest

fun dependency(group: String, name: String, version: String,
               classifier:Classifier = NoClassifier, type:String = DEFAULT_TYPE, scope:wemi.dependency.DepScope = DEFAULT_SCOPE,
               optional:Boolean = DEFAULT_OPTIONAL, snapshotVersion:String = DEFAULT_SNAPSHOT_VERSION,
               exclusions:List<DependencyExclusion> = emptyList(),
               dependencyManagement:List<Dependency> = emptyList()): Dependency =
        wemi.dependency(group, name, version, classifier, type, scope, optional, snapshotVersion, exclusions, dependencyManagement)

fun dependency(groupNameVersionClassifierType: String,
               scope:wemi.dependency.DepScope = DEFAULT_SCOPE, optional:Boolean = DEFAULT_OPTIONAL,
               exclusions:List<DependencyExclusion> = emptyList(),
               snapshotVersion:String = DEFAULT_SNAPSHOT_VERSION,
               dependencyManagement:List<Dependency> = emptyList()): Dependency =
        wemi.dependency(groupNameVersionClassifierType, scope, optional, exclusions, snapshotVersion, dependencyManagement)

// Path helpers
/**
 * Construct a path, from given string, like with [java.nio.file.Paths.get].
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
    inline get() = Keys.projectGroup
val projectName
    inline get() = Keys.projectName
val projectVersion
    inline get() = Keys.projectVersion

val projectRoot
    inline get() = Keys.projectRoot
val buildDirectory
    inline get() = Keys.buildDirectory
val cacheDirectory
    inline get() = Keys.cacheDirectory

val sources
    inline get() = Keys.sources
val resources
    inline get() = Keys.resources

val repositories
    inline get() = Keys.repositories
val libraryDependencies
    inline get() = Keys.libraryDependencies
val libraryDependencyMapper
    inline get() = Keys.libraryDependencyMapper
val resolvedLibraryScopes
    inline get() = Keys.resolvedLibraryScopes
val resolvedLibraryDependencies
    inline get() = Keys.resolvedLibraryDependencies
val unmanagedDependencies
    inline get() = Keys.unmanagedDependencies
val projectDependencies
    inline get() = Keys.projectDependencies

val externalClasspath
    inline get() = Keys.externalClasspath
val internalClasspath
    inline get() = Keys.internalClasspath

val javaHome
    inline get() = Keys.javaHome
val javaExecutable
    inline get() = Keys.javaExecutable
val kotlinVersion
    inline get() = Keys.kotlinVersion
val compilerOptions
    inline get() = Keys.compilerOptions
val outputClassesDirectory
    inline get() = Keys.outputClassesDirectory
val outputSourcesDirectory
    inline get() = Keys.outputSourcesDirectory
val outputHeadersDirectory
    inline get() = Keys.outputHeadersDirectory
val kotlinCompiler
    inline get() = Keys.kotlinCompiler
val javaCompiler
    inline get() = Keys.javaCompiler
val compile
    inline get() = Keys.compile

val mainClass
    inline get() = Keys.mainClass
val runDirectory
    inline get() = Keys.runDirectory
val runOptions
    inline get() = Keys.runOptions
val runArguments
    inline get() = Keys.runArguments
val run
    inline get() = Keys.run
val runMain
    inline get() = Keys.runMain

val testParameters
    inline get() = Keys.testParameters
val test
    inline get() = Keys.test

val archiveOutputFile
    inline get() = Keys.archiveOutputFile
val archiveJavadocOptions
    inline get() = Keys.archiveJavadocOptions
val archiveDokkaOptions
    inline get() = Keys.archiveDokkaOptions
val archiveDokkaInterface
    inline get() = Keys.archiveDokkaInterface
val archive
    inline get() = Keys.archive

val publishMetadata
    inline get() = Keys.publishMetadata
val publishRepository
    inline get() = Keys.publishRepository
val publishArtifacts
    inline get() = Keys.publishArtifacts
val publish
    inline get() = Keys.publish

val assemblyMergeStrategy
    inline get() = Keys.assemblyMergeStrategy
val assemblyRenameFunction
    inline get() = Keys.assemblyRenameFunction
val assemblyMapFilter
    inline get() = Keys.assemblyMapFilter
val assemblyPrependData
    inline get() = Keys.assemblyPrependData
val assemblyOutputFile
    inline get() = Keys.assemblyOutputFile
val assembly
    inline get() = Keys.assembly

// Build script directive annotations
typealias BuildDependency = wemi.boot.BuildDependency
typealias BuildDependencyRepository = wemi.boot.BuildDependencyRepository
typealias BuildClasspathDependency = wemi.boot.BuildClasspathDependency