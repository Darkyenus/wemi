@file:Suppress("NOTHING_TO_INLINE", "unused")

import wemi.Archetypes
import wemi.CACHE_FOR_RUN
import wemi.Configurations
import wemi.KeyCacheMode
import wemi.boot.WemiRootFolder
import wemi.dependency.DependencyAttribute
import wemi.dependency.Repository
import wemi.util.div as _div
import java.net.URL
import java.nio.file.Paths
import wemi.kotlinDependency as _kotlinDependency
import wemi.dependency.dependency as _dependency
import wemi.test.JUnitAPI as _JUnitAPI
import wemi.test.JUnitEngine as _JUnitEngine
import wemi.collections.toWSet as _toWSet
import wemi.collections.toWList as _toWList

/*
 * Types and values that should be visible in build scripts without any explicit imports
 */

// Types
typealias Key<Value> = wemi.Key<Value>
typealias Archetype = wemi.Archetype
typealias Configuration = wemi.Configuration
typealias Project = wemi.Project
typealias Scope = wemi.Scope
typealias Repository = wemi.dependency.Repository
typealias ProjectId = wemi.dependency.DependencyId
typealias ProjectDependency = wemi.dependency.Dependency
typealias ProjectExclusion = wemi.dependency.DependencyExclusion

typealias WCollection<T> = wemi.collections.WCollection<T>
typealias WSet<T> = wemi.collections.WSet<T>
typealias WList<T> = wemi.collections.WList<T>
typealias WMutableCollection<T> = wemi.collections.WMutableCollection<T>
typealias WMutableSet<T> = wemi.collections.WMutableSet<T>
typealias WMutableList<T> = wemi.collections.WMutableList<T>

typealias Path = java.nio.file.Path

// Core Functions
inline fun project(vararg archetypes: Archetype = arrayOf(Archetypes.DefaultArchetype), noinline initializer: Project.() -> Unit) = wemi.project(path("."), archetypes = *archetypes, initializer = initializer)
inline fun project(projectRoot: Path? = path("."), vararg archetypes: Archetype = arrayOf(Archetypes.DefaultArchetype), noinline initializer: Project.() -> Unit) = wemi.project(projectRoot, *archetypes, initializer = initializer)

inline fun <Value> key(description: String, defaultValue: Value, cacheMode: KeyCacheMode<Value>? = CACHE_FOR_RUN) = wemi.key(description, defaultValue, cacheMode)
inline fun <Value> key(description: String, cacheMode: KeyCacheMode<Value>? = CACHE_FOR_RUN) = wemi.key(description, cacheMode)

inline fun configuration(description: String, parent: Configuration? = null, noinline initializer: Configuration.() -> Unit) = wemi.configuration(description, parent, initializer)

inline fun dependency(group: String, name: String, version: String, preferredRepository: Repository?, vararg attributes: Pair<DependencyAttribute, String>) = wemi.dependency(group, name, version, preferredRepository, *attributes)
inline fun dependency(group: String, name: String, version: String, vararg attributes: Pair<DependencyAttribute, String>) = wemi.dependency(group, name, version, null, *attributes)
inline fun dependency(groupNameVersion: String, preferredRepository: Repository?, vararg attributes: Pair<DependencyAttribute, String>) = wemi.dependency(groupNameVersion, preferredRepository, *attributes)
inline fun dependency(groupNameVersion: String, vararg attributes: Pair<DependencyAttribute, String>) = wemi.dependency(groupNameVersion, null, *attributes)

inline fun repository(name: String, url: String, checksum: Repository.M2.Checksum = Repository.M2.Checksum.SHA1) = wemi.repository(name, url, checksum)

// Helper functions
inline fun Scope.kotlinDependency(name: String) = _kotlinDependency(name)
inline val Scope.JUnitAPI
    inline get() = _JUnitAPI
val Scope.JUnitEngine
    inline get() = _JUnitEngine
inline fun dependency(project:Project, aggregate:Boolean, vararg configurations:Configuration) = _dependency(project, aggregate, *configurations)

@Deprecated("User version with explicit aggregate=true parameter.")
inline fun dependency(project:Project, vararg configurations:Configuration) = _dependency(project, true, *configurations)

// WCollection
inline fun <T> wEmptySet() = wemi.collections.wEmptySet<T>()
inline fun <T> wEmptyList() = wemi.collections.wEmptyList<T>()
inline fun <T> wSetOf(vararg items:T) = wemi.collections.wSetOf(*items)
inline fun <T> wMutableSetOf(vararg items:T) = wemi.collections.wMutableSetOf(*items)
inline fun <T> wListOf(vararg items:T) = wemi.collections.wListOf(*items)
inline fun <T> wMutableListOf(vararg items:T) = wemi.collections.wMutableListOf(*items)
inline fun <T> Collection<T>.toWSet() = this._toWSet()
inline fun <T> Collection<T>.toWList() = this._toWList()

// Path helpers
/**
 * Construct a path, from given string, like with [Paths.get].
 * If the resulting path is relative, make it absolute by resolving it on [WemiRootFolder].
 * Resulting path is normalized (though [java.nio.file.Path.normalize]).
 */
fun path(path:String):Path {
    val result = Paths.get(path)
    if (result.isAbsolute) {
        return result.normalize()
    } else {
        return WemiRootFolder.resolve(result).normalize()
    }
}
inline operator fun URL.div(path: CharSequence): URL = this._div(path)
inline operator fun Path.div(path: CharSequence): Path = this._div(path)
inline operator fun CharSequence.div(path: CharSequence): StringBuilder = this._div(path)

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

val compilingConfigurations
    inline get() = wemi.Keys.compilingConfigurations

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
val projectDependencies
    inline get() = wemi.Keys.projectDependencies

val externalClasspath
    inline get() = wemi.Keys.externalClasspath
val internalClasspath
    inline get() = wemi.Keys.internalClasspath

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