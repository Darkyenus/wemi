@file:Suppress("MemberVisibilityCanBePrivate", "unused", "ObjectPropertyName")

package wemi

import wemi.KeyDefaults.externalClasspathWithClassifier
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.JarMergeStrategyChooser
import wemi.boot.WemiBuildFolder
import wemi.boot.WemiCacheFolder
import wemi.compile.CompilerFlags
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.dependency.DefaultRepositories
import wemi.dependency.Dependency
import wemi.dependency.JavadocClassifier
import wemi.dependency.LocalM2Repository
import wemi.dependency.NoClassifier
import wemi.dependency.ScopeTest
import wemi.dependency.SourcesClassifier
import wemi.publish.artifacts
import wemi.run.javaExecutable
import wemi.test.JUnitAPI
import wemi.test.JUnitEngine
import wemi.test.JUnitPlatformLauncher
import wemi.util.FileSet
import wemi.util.changeExtensionAndMove
import wemi.util.div
import wemi.util.toValidIdentifier
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
        Keys.internalClasspath set KeyDefaults.internalClasspath(true)
        Keys.externalClasspath set KeyDefaults.ExternalClasspath

        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers")
        Keys.outputJavascriptDirectory set KeyDefaults.outputClassesDirectory("javascript")
        Keys.compilerOptions set { CompilerFlags() }

        Keys.runDirectory set { Keys.projectRoot.get() }

        Keys.archive set KeyDefaults.Archive
        Keys.archiveSources set KeyDefaults.ArchiveSources
        Keys.archiveDocs set KeyDefaults.ArchiveDummyDocumentation

        Keys.externalSources set externalClasspathWithClassifier(SourcesClassifier)
        Keys.externalDocs set externalClasspathWithClassifier(JavadocClassifier)
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

        Keys.archiveSources modify { it.changeExtensionAndMove("jar") }

        Keys.publishMetadata set KeyDefaults.PublishModelM2
        Keys.publishRepository set Static(LocalM2Repository)
        Keys.publishArtifacts addAll { artifacts(NoClassifier, includeSources = true, includeDocumentation = true) }
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
    internal val DefaultArchetypes
        get() = arrayOf(JavaKotlinProject, JUnitLayer)

    /** An archetype for projects that have no sources of their own, but are aggregation of their dependencies. */
    val AggregateJVMProject by archetype(::JVMBase) {
        Keys.internalClasspath set KeyDefaults.internalClasspath(false)

        Keys.test set KeyDefaults.TestOfAggregateProject
    }

    /** A primary archetype for projects that use pure Java. */
    val JavaProject by archetype(::JVMBase) {
        Keys.sources set { FileSet(Keys.projectRoot.get() / "src/main/java") }
        Keys.testSources set { FileSet(Keys.projectRoot.get() / "src/test/java") }
        Keys.resources set { FileSet(Keys.projectRoot.get() / "src/main/resources") }
        Keys.testResources set { FileSet(Keys.projectRoot.get() / "src/test/resources") }

        Keys.compilerOptions[JavaCompilerFlags.customFlags] = { it + "-g" }
        Keys.compilerOptions[JavaCompilerFlags.sourceVersion] = { "1.8" }
        Keys.compilerOptions[JavaCompilerFlags.targetVersion] = { "1.8" }
        Keys.compilerOptions[JavaCompilerFlags.encoding] = { "UTF-8" }
        Keys.compile set KeyDefaults.CompileJava

        Keys.archiveJavadocOptions set KeyDefaults.ArchiveJavadocOptions
        Keys.archiveDocs set KeyDefaults.ArchiveJavadoc
    }

    /** A primary archetype for projects that use Java and Kotlin. */
    val JavaKotlinProject by archetype(::JVMBase) {
        Keys.sources set {
            val root = Keys.projectRoot.get()
            FileSet(root / "src/main/java", next = FileSet(root / "src/main/kotlin"))
        }
        Keys.resources set { FileSet(Keys.projectRoot.get() / "src/main/resources") }
        Keys.testSources set {
            val root = Keys.projectRoot.get()
            FileSet(root / "src/test/java", next = FileSet(root / "src/test/kotlin"))
        }
        Keys.testResources set { FileSet(Keys.projectRoot.get() / "src/test/resources") }

        Keys.libraryDependencies add { kotlinDependency("stdlib") }

        Keys.compilerOptions[JavaCompilerFlags.customFlags] = { it + "-g" }
        Keys.compilerOptions[JavaCompilerFlags.sourceVersion] = { "1.8" }
        Keys.compilerOptions[JavaCompilerFlags.targetVersion] = { "1.8" }
        Keys.compilerOptions[KotlinCompilerFlags.moduleName] = { Keys.projectName.get().toValidIdentifier() ?: "myModule" }
        Keys.compilerOptions[KotlinJVMCompilerFlags.jvmTarget] = { "1.8" }

        Keys.kotlinCompiler set { Keys.kotlinVersion.get().compilerInstance(progressListener) }
        Keys.compile set KeyDefaults.CompileJavaKotlin

        Keys.archiveDokkaOptions set KeyDefaults.ArchiveDokkaOptions
        Keys.archiveDokkaInterface set KeyDefaults.ArchiveDokkaInterface
        Keys.archiveDocs set KeyDefaults.ArchiveDokka
    }

    /** Primary archetype for projects that produce JavaScript source files as output */
    val KotlinJSProject by archetype(::Base) {
        Keys.repositories set Static(DefaultRepositories)

        Keys.sources set { FileSet(Keys.projectRoot.get() / "src/main/kotlin") }
        Keys.testSources set { FileSet(Keys.projectRoot.get() / "src/test/kotlin") }
        Keys.resources set { FileSet(Keys.projectRoot.get() / "src/main/resources") }
        Keys.testResources set { FileSet(Keys.projectRoot.get() / "src/test/resources") }

        Keys.libraryDependencies add { kotlinDependency("stdlib-js") }

        Keys.compilerOptions[KotlinCompilerFlags.moduleName] = { Keys.projectName.get().toValidIdentifier() ?: "myModule" }

        Keys.kotlinCompiler set { Keys.kotlinVersion.get().compilerInstance(progressListener) }
        Keys.compile set KeyDefaults.CompileKotlinJS
    }

    //endregion

    //region Layer archetypes

    /** An archetype layer adding support for JUnit testing.
     * Intended to be used with other [JVMBase]-based archetype. */
    val JUnitLayer by archetype {
        Keys.testParameters set KeyDefaults.TestParameters
        Keys.test set KeyDefaults.Test
        extend(Configurations.testing) {
            Keys.libraryDependencies addAll { listOf(
                    Dependency(JUnitAPI, scope=ScopeTest),
                    Dependency(JUnitEngine, scope=ScopeTest),
                    Dependency(JUnitPlatformLauncher, scope=ScopeTest)
            ) }
        }
    }

    @Deprecated("Use JUnitLayer instead", ReplaceWith("Archetypes.JUnitLayer", "wemi.Archetypes"))
    val JUnitProject = JUnitLayer

    //endregion
}