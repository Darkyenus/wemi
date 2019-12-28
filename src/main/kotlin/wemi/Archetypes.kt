@file:Suppress("MemberVisibilityCanBePrivate", "unused", "ObjectPropertyName")

package wemi

import wemi.assembly.DefaultRenameFunction
import wemi.assembly.JarMergeStrategyChooser
import wemi.boot.WemiBuildFolder
import wemi.boot.WemiCacheFolder
import wemi.compile.CompilerFlags
import wemi.dependency.DefaultRepositories
import wemi.dependency.LocalM2Repository
import wemi.dependency.NoClassifier
import wemi.publish.artifacts
import wemi.run.javaExecutable
import wemi.util.FileSet
import wemi.util.div
import wemi.util.plus
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
        Keys.buildDirectory set Static(WemiBuildFolder)
        Keys.cacheDirectory set Static(WemiCacheFolder)

        Keys.resolvedLibraryDependencies set KeyDefaults.ResolvedLibraryDependencies
        Keys.internalClasspath set KeyDefaults.InternalClasspath
        Keys.externalClasspath set KeyDefaults.ExternalClasspath

        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers")
        Keys.compilerOptions set { CompilerFlags() }

        Keys.runDirectory set { Keys.projectRoot.get() }

        Keys.archiveOutputFile set { Keys.buildDirectory.get() / "${Keys.projectName.get()}-${Keys.projectVersion.get()}.zip" }
        Keys.archive set KeyDefaults.Archive
    }

    /**
     * Archetypal base for projects that run on JVM.
     * Don't use this directly in [Project], this is meant for [Archetype] creators.
     *
     * Implements basic key implementations for JVM that don't usually change.
     */
    val JVMBase by archetype(::Base) {
        Keys.repositories set Static(DefaultRepositories)

        Keys.javaHome set Static(wemi.run.JavaHome)
        Keys.javaExecutable set { javaExecutable(Keys.javaHome.get()) }
        Keys.javaCompiler set LazyStatic {
            ToolProvider.getSystemJavaCompiler()
                ?: throw WemiException("Could not find Java Compiler in the classpath, ensure that you are running Wemi from JDK-bundled JRE. " +
                        "Default java.home property is set to \"${System.getProperty("java.home")}\".")
        }

        //Keys.mainClass TODO Detect main class?
        Keys.runOptions set KeyDefaults.RunOptions
        Keys.run set KeyDefaults.Run
        Keys.runMain set KeyDefaults.RunMain

        Keys.testParameters set KeyDefaults.TestParameters
        Keys.test set KeyDefaults.Test

        Keys.archiveOutputFile set { Keys.buildDirectory.get() / "${Keys.projectName.get()}-${Keys.projectVersion.get()}.jar" }

        Keys.publishMetadata set KeyDefaults.PublishModelM2
        Keys.publishRepository set Static(LocalM2Repository)
        Keys.publishArtifacts addAll { artifacts(NoClassifier, true, true) }
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

    /** An archetype for projects that have no sources of their own, but are aggregation of their dependencies. */
    val AggregateJVMProject by archetype(::JVMBase) {
        Keys.internalClasspath set KeyDefaults.InternalClasspathOfAggregateProject

        Keys.test set KeyDefaults.TestOfAggregateProject
    }

    /** A primary archetype for projects that use pure Java. */
    val JavaProject by archetype(::JVMBase) {
        Keys.sources set { FileSet(Keys.projectRoot.get() / "src/main/java") }
        extend (Configurations.testing) {
            Keys.sources modify { FileSet(Keys.projectRoot.get() / "src/test/java", next = it) }
        }
        Keys.resources set { FileSet(Keys.projectRoot.get() / "src/main/resources") }
        extend (Configurations.testing) {
            Keys.resources modify { it + FileSet(Keys.projectRoot.get() / "src/test/resources") }
        }

        Keys.compile set KeyDefaults.CompileJava

        Keys.archiveJavadocOptions set KeyDefaults.ArchiveJavadocOptions

        extend(Configurations.archivingDocs) {
            Keys.archive set KeyDefaults.ArchiveJavadoc
        }
    }

    /** A primary archetype for projects that use Java and Kotlin. */
    val JavaKotlinProject by archetype(::JVMBase) {
        Keys.sources set {
            val root = Keys.projectRoot.get()
            FileSet(root / "src/main/java", next = FileSet(root / "src/main/kotlin"))
        }
        Keys.resources set { FileSet(Keys.projectRoot.get() / "src/main/resources") }
        extend (Configurations.testing) {
            Keys.sources modify {
                val root = Keys.projectRoot.get()
                FileSet(root / "src/test/java", next = FileSet(root / "src/test/kotlin", next = it))
            }
            Keys.resources modify { it + FileSet(Keys.projectRoot.get() / "src/test/resources") }
        }

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