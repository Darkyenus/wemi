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
import wemi.publish.artifacts
import wemi.run.javaExecutable
import wemi.util.div
import javax.tools.ToolProvider

/**
 * Default Wemi project [Archetype]s.
 *
 * There are two kinds of archetypes:
 * - Primary
 *      - Project can have only one primary archetype, as those setup things like compiler, source folders,
 *       and generally dictate what language project will use and what will be the result of [Keys.compile].
 *      - `*Base` named serve as a parents for other `*Base` and `*Project` archetypes.
 *          Do not use them directly as your project's archetype.
 *      - `*Project` named are to be used by the user defined projects and generally inherit from a `*Base` archetype
 * - Secondary
 *      - Secondary archetypes add additional functionality on top of existing project.
 *      There may be zero or more secondary archetypes per project.
 *      - These are named `*Facet`
 */
object Archetypes {

    /**
     * Archetypal base. All primary archetypes must use this as a parent archetype.
     * Don't use this directly in [Project], this is meant for [Archetype] creators.
     *
     * Implements basic key implementations that don't usually change.
     */
    val Base by archetype {
        Keys.input set Static(InputBase(WemiRunningInInteractiveMode))

        Keys.buildDirectory set Static(WemiBuildFolder)
        Keys.cacheDirectory set Static(WemiCacheFolder)

        Keys.resourceFiles set KeyDefaults.ResourceFiles
        Keys.sourceFiles set KeyDefaults.SourceFiles

        Keys.repositoryChain set { createRepositoryChain(Keys.repositories.get()) }
        Keys.resolvedLibraryDependencies set KeyDefaults.ResolvedLibraryDependencies
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
    val JVMBase by archetype(::Base) {
        Keys.resourceRoots set { setOf(Keys.projectRoot.get() / "src/main/resources") }
        extend (Configurations.testing) {
            Keys.resourceRoots add { Keys.projectRoot.get() / "src/test/resources" }
        }

        Keys.sourceRoots setToUnionOfSelfIn { Keys.compilingConfigurations.get() }
        Keys.sourceFiles setToConcatenationOfSelfIn { Keys.compilingConfigurations.get() }

        Keys.repositories set { DefaultRepositories }

        Keys.javaHome set Static(wemi.run.JavaHome)
        Keys.javaExecutable set { javaExecutable(Keys.javaHome.get()) }
        Keys.javaCompiler set LazyStatic { ToolProvider.getSystemJavaCompiler() }

        //Keys.mainClass TODO Detect main class?
        Keys.runOptions set KeyDefaults.RunOptions
        Keys.run set KeyDefaults.Run
        Keys.runMain set KeyDefaults.RunMain

        Keys.testParameters set KeyDefaults.TestParameters
        Keys.test set KeyDefaults.Test

        Keys.archiveOutputFile set { Keys.buildDirectory.get() / "${Keys.projectName.get()}-${Keys.projectVersion.get()}.jar" }

        Keys.publishMetadata set KeyDefaults.PublishModelM2
        Keys.publishRepository set Static(LocalM2Repository)
        Keys.publishArtifacts addAll { artifacts(null, true, true) }
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
     * Archetype for projects that have no sources of their own.
     * Those can serve as an aggregation of dependencies or as a Maven-like parent projects.
     */
    val BlankJVMProject by archetype(::JVMBase) {
        Keys.internalClasspath set Static(emptyList())

        Keys.resourceRoots set Static(emptySet())
    }

    /**
     * Primary archetype for projects that use pure Java.
     */
    val JavaProject by archetype(::JVMBase) {
        Keys.compilingConfigurations set Static(setOf(Configurations.compilingJava))

        Keys.compile set KeyDefaults.CompileJava

        Keys.archiveJavadocOptions set KeyDefaults.ArchiveJavadocOptions

        extend(Configurations.archivingDocs) {
            Keys.archive set KeyDefaults.ArchiveJavadoc
        }
    }

    /**
     * Primary archetype for projects that use Java and Kotlin.
     */
    val JavaKotlinProject by archetype(::JVMBase) {
        Keys.compilingConfigurations set Static(setOf(Configurations.compilingJava, Configurations.compilingKotlin))

        Keys.libraryDependencies add { kotlinDependency("stdlib") }
        Keys.compile set KeyDefaults.CompileJavaKotlin

        Keys.archiveDokkaOptions set KeyDefaults.ArchiveDokkaOptions
        Keys.archiveDokkaInterface set KeyDefaults.ArchiveDokkaInterface

        extend(Configurations.archivingDocs) {
            Keys.archive set KeyDefaults.ArchiveDokka
        }
    }

    //endregion
}