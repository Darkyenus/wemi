@file:Suppress("MemberVisibilityCanBePrivate", "unused", "ObjectPropertyName")

package wemi

import wemi.assembly.DefaultRenameFunction
import wemi.assembly.JarMergeStrategyChooser
import wemi.boot.WemiBuildFolder
import wemi.boot.WemiCacheFolder
import wemi.boot.WemiRunningInInteractiveMode
import wemi.compile.CompilerFlags
import wemi.dependency.DefaultRepositories
import wemi.dependency.LocalM2Repository
import wemi.dependency.createRepositoryChain
import wemi.run.javaExecutable
import wemi.util.*
import java.nio.file.Path
import javax.tools.ToolProvider

/**
 * Default Wemi project [Archetype]s.
 *
 * There are two kinds of archetypes. Primary and secondary.
 * Project can have only one primary archetype, as those setup things like compiler, source folders,
 * and generally dictate what language project will use and what will be the result of [Keys.compile].
 *
 * Secondary archetypes, on the other hand, add additional functionality on top of existing project.
 * There may be zero or more secondary archetypes, although this depends on the functionality provided.
 *
 * Base archetypes whose names are surrounded with underscores (_) are not meant for direct use.
 */
object Archetypes {

    /**
     * Archetypal base. All primary archetypes must use this as a parent archetype.
     * Don't use this directly in [Project], this is meant for [Archetype] creators.
     *
     * Implements basic key implementations that don't usually change.
     */
    val _Base_ by archetype {
        Keys.input set { InputBase(WemiRunningInInteractiveMode) }

        Keys.buildDirectory set { WemiBuildFolder }
        Keys.cacheDirectory set { WemiCacheFolder }
        Keys.sourceBases set { wMutableSetOf(Keys.projectRoot.get() / "src/main") }
        // Source files and roots are set elsewhere

        Keys.resourceRoots set KeyDefaults.ResourceRoots
        Keys.resourceFiles set KeyDefaults.ResourceFiles

        Keys.repositoryChain set { createRepositoryChain(Keys.repositories.get()) }
        Keys.resolvedLibraryDependencies set KeyDefaults.ResolvedLibraryDependencies
        Keys.resolvedProjectDependencies set KeyDefaults.ResolvedProjectDependencies
        Keys.internalClasspath set KeyDefaults.InternalClasspath
        Keys.externalClasspath set KeyDefaults.ExternalClasspath

        Keys.clean set KeyDefaults.Clean

        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers")
        Keys.compilerOptions set { CompilerFlags() }

        Keys.runDirectory set { Keys.projectRoot.get() }

        Keys.archiveOutputFile set { Keys.buildDirectory.get() / "${Keys.projectName.get()}-${Keys.projectVersion.get()}.zip" }
        Keys.archive set KeyDefaults.Archive

        extend(Configurations.publishing) {
            Keys.archive set KeyDefaults.ArchivePublishing
        }
    }

    /**
     * Archetypal base for projects that run on JVM.
     * Don't use this directly in [Project], this is meant for [Archetype] creators.
     *
     * Implements basic key implementations for JVM that don't usually change.
     */
    val _JVMBase_ by archetype(::_Base_) {
        Keys.repositories set { DefaultRepositories }

        Keys.javaHome set { wemi.run.JavaHome }
        Keys.javaExecutable set { javaExecutable(Keys.javaHome.get()) }
        Keys.javaCompiler set { ToolProvider.getSystemJavaCompiler() }

        //Keys.mainClass TODO Detect main class?
        Keys.runOptions set KeyDefaults.RunOptions
        Keys.run set KeyDefaults.Run
        Keys.runMain set KeyDefaults.RunMain

        Keys.testParameters set KeyDefaults.TestParameters
        Keys.test set KeyDefaults.Test

        Keys.archiveOutputFile set { Keys.buildDirectory.get() / "${Keys.projectName.get()}-${Keys.projectVersion.get()}.jar" }

        Keys.publishMetadata set KeyDefaults.PublishModelM2
        Keys.publishRepository set { LocalM2Repository }
        Keys.publish set KeyDefaults.PublishM2

        Keys.assemblyMergeStrategy set {
            JarMergeStrategyChooser
        }
        Keys.assemblyRenameFunction set {
            DefaultRenameFunction
        }
        Keys.assemblyOutputFile set { Keys.buildDirectory.get() / (Keys.projectName.get() + "-" + Keys.projectVersion.get() + "-assembly.jar") }
        Keys.assembly set KeyDefaults.Assembly
    }

    //region Primary Archetypes

    @PublishedApi
    internal val DefaultArchetype
        get() = JavaKotlinProject

    /**
     * Archetype for projects that serve only as an aggregation of dependencies and do not have any own sources.
     */
    val DependenciesOnly by archetype(::_JVMBase_) {
        //TODO IDE still detects some sources here
        Keys.internalClasspath set { wEmptyList() }

        Keys.sourceBases set { wEmptySet() }
        Keys.sourceRoots set { wEmptySet() }
        Keys.sourceFiles set { wEmptyList() }

        Keys.resourceRoots set { wEmptySet() }
        Keys.resourceFiles set { wEmptyList() }
    }

    /**
     * Primary archetype for projects that use pure Java.
     */
    val JavaProject by archetype(::_JVMBase_) {
        Keys.sourceRoots set { using(Configurations.compilingJava) { Keys.sourceRoots.get() } }
        Keys.sourceFiles set { using(Configurations.compilingJava) { Keys.sourceFiles.get() } }
        Keys.compile set KeyDefaults.CompileJava

        extend(Configurations.archivingDocs) {
            Keys.archiveJavadocOptions set KeyDefaults.ArchiveJavadocOptions
            Keys.archive set KeyDefaults.ArchiveJavadoc
        }
    }

    /**
     * Primary archetype for projects that use Java and Kotlin.
     */
    val JavaKotlinProject by archetype(::_JVMBase_) {
        Keys.sourceRoots set {
            val java = using(Configurations.compilingJava) { Keys.sourceRoots.get() }
            val kotlin = using(Configurations.compilingKotlin) { Keys.sourceRoots.get() }
            val files = WMutableSet<Path>(java.size + kotlin.size)
            files.addAll(java)
            files.addAll(kotlin)
            files
        }
        Keys.sourceFiles set {
            val java = using(Configurations.compilingJava) { Keys.sourceFiles.get() }
            val kotlin = using(Configurations.compilingKotlin) { Keys.sourceFiles.get() }
            val files = WMutableList<LocatedFile>(java.size + kotlin.size)
            files.addAll(java)
            files.addAll(kotlin)
            files
        }
        Keys.libraryDependencies add { kotlinDependency("stdlib") }
        Keys.compile set KeyDefaults.CompileJavaKotlin

        extend(Configurations.archivingDocs) {
            Keys.archiveDokkaOptions set KeyDefaults.ArchiveDokkaOptions
            Keys.archiveDokkaInterface set KeyDefaults.ArchiveDokkaInterface
            Keys.archive set KeyDefaults.ArchiveDokka
        }
    }

    //endregion

}