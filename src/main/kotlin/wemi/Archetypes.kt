@file:Suppress("MemberVisibilityCanBePrivate", "unused", "ObjectPropertyName")

package wemi.archetypes

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
import wemi.test.JUnitAPI
import wemi.test.JUnitEngine
import wemi.test.JUnitPlatformLauncher
import wemi.util.CurrentProcessJavaHome
import wemi.util.FileSet
import wemi.util.changeExtensionAndMove
import wemi.util.div
import wemi.util.toValidIdentifier
import javax.tools.ToolProvider
import wemi.*
import wemi.configurations.ideImport
import wemi.configurations.testing
import wemi.keys.*

/*
 * Default Wemi project [Archetype]s.
 *
 * There are two kinds of archetypes:
 * - Primary
 *      - Project can have only one primary archetype, as those setup things like compiler, source folders,
 *       and generally dictate what language project will use and what will be the result of [keys.compile].
 *      - `*Base` named serve as a parents for other `*Base` and `*Project` archetypes.
 *          Do not use them directly as your project's archetype.
 *      - `*Project` named are to be used by the user defined projects and generally inherit from a `*Base` archetype
 * - Secondary
 *      - Secondary archetypes add additional functionality on top of existing project.
 *      There may be zero or more secondary archetypes per project.
 *      - These are named `*Facet`
 */

/**
 * Archetypal base. All primary archetypes must use this as a parent archetype.
 * Don't use this directly in [Project], this is meant for [Archetype] creators.
 *
 * Implements basic key implementations that don't usually change.
 */
val Base by archetype {
    buildDirectory put WemiBuildFolder
    cacheDirectory put WemiCacheFolder

    resolvedLibraryDependencies set KeyDefaults.ResolvedLibraryDependencies
    internalClasspath set KeyDefaults.internalClasspath(compile = true)
    externalClasspath set KeyDefaults.ExternalClasspath

    outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes")
    outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources")
    outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers")
    outputJavascriptDirectory set KeyDefaults.outputClassesDirectory("javascript")
    compilerOptions set { CompilerFlags() }

    runDirectory set { projectRoot.get() }
    runEnvironment set { System.getenv() }

    archive set KeyDefaults.Archive
    archiveSources set KeyDefaults.ArchiveSources
    archiveDocs set KeyDefaults.ArchiveDummyDocumentation

    externalSources set externalClasspathWithClassifier(SourcesClassifier)
    externalDocs set externalClasspathWithClassifier(JavadocClassifier)
}

/**
 * Archetypal base for projects that run on JVM.
 * Don't use this directly in [Project], this is meant for [Archetype] creators.
 *
 * Implements basic key implementations for JVM that don't usually change.
 */
val JVMBase by archetype(::Base) {
    repositories put DefaultRepositories

    javaHome put CurrentProcessJavaHome
    javaCompiler putLazy {
        ToolProvider.getSystemJavaCompiler()
            ?: throw WemiException("Could not find Java Compiler in the classpath, ensure that you are running Wemi from JDK-bundled JRE. " +
                    "Default java.home property is set to \"${System.getProperty("java.home")}\".")
    }

    //Keys.mainClass TODO Detect main class?
    runOptions set KeyDefaults.RunOptions
    runProcess set KeyDefaults.RunProcess
    run set KeyDefaults.Run

    archiveSources modify { it.changeExtensionAndMove("jar") }

    publishMetadata set KeyDefaults.PublishModelM2
    publishRepository put LocalM2Repository
    publishArtifacts addAll { artifacts(NoClassifier, includeSources = true, includeDocumentation = true) }
    publish set KeyDefaults.PublishM2

    assemblyMergeStrategy put JarMergeStrategyChooser
    assemblyRenameFunction put DefaultRenameFunction

    assemblyOutputFile set { buildDirectory.get() / (projectName.get() + "-" + projectVersion.get() + "-assembly.jar") }
    assembly set KeyDefaults.Assembly
}

//region Primary Archetypes

@PublishedApi
internal val DefaultArchetypes
    get() = arrayOf(JavaKotlinProject, JUnitLayer)

/** An archetype for projects that have no sources of their own, but are aggregation of their dependencies. */
val AggregateJVMProject by archetype(::JVMBase) {
    internalClasspath set KeyDefaults.internalClasspath(compile = false)

    test set KeyDefaults.TestOfAggregateProject
}

/** A primary archetype for projects that use pure Java. */
val JavaProject by archetype(::JVMBase) {
    sources set { FileSet(projectRoot.get() / "src/main/java") }
    testSources set { FileSet(projectRoot.get() / "src/test/java") }
    resources set { FileSet(projectRoot.get() / "src/main/resources") }
    testResources set { FileSet(projectRoot.get() / "src/test/resources") }

    compilerOptions[JavaCompilerFlags.customFlags] = { it + "-g" }
    compilerOptions[JavaCompilerFlags.sourceVersion] = { "1.8" }
    compilerOptions[JavaCompilerFlags.targetVersion] = { "1.8" }
    compilerOptions[JavaCompilerFlags.encoding] = { "UTF-8" }
    compile set KeyDefaults.CompileJava

    archiveJavadocOptions set KeyDefaults.ArchiveJavadocOptions
    archiveDocs set KeyDefaults.ArchiveJavadoc
}

/** A primary archetype for projects that use Java and Kotlin. */
val JavaKotlinProject by archetype(::JVMBase) {
    sources set {
        val root = projectRoot.get()
        FileSet(root / "src/main/java", next = FileSet(root / "src/main/kotlin"))
    }
    resources set { FileSet(projectRoot.get() / "src/main/resources") }
    testSources set {
        val root = projectRoot.get()
        FileSet(root / "src/test/java", next = FileSet(root / "src/test/kotlin"))
    }
    testResources set { FileSet(projectRoot.get() / "src/test/resources") }

    libraryDependencies addAll { if (automaticKotlinStdlib.get()) listOf(kotlinDependency("stdlib")) else emptyList() }

    compilerOptions[JavaCompilerFlags.customFlags] = { it + "-g" }
    compilerOptions[JavaCompilerFlags.sourceVersion] = { "1.8" }
    compilerOptions[JavaCompilerFlags.targetVersion] = { "1.8" }
    compilerOptions[KotlinCompilerFlags.moduleName] = { projectName.get().toValidIdentifier() ?: "myModule" }
    compilerOptions[KotlinJVMCompilerFlags.jvmTarget] = { "1.8" }

    kotlinCompiler set { kotlinVersion.get().compilerInstance(progressListener) }
    compile set KeyDefaults.CompileJavaKotlin

    archiveDokkaOptions set KeyDefaults.ArchiveDokkaOptions
    archiveDokkaInterface set KeyDefaults.ArchiveDokkaInterface
    archiveDocs set KeyDefaults.ArchiveDokka
}

/** Primary archetype for projects that produce JavaScript source files as output */
val KotlinJSProject by archetype(::Base) {
    repositories put DefaultRepositories

    sources set { FileSet(projectRoot.get() / "src/main/kotlin") }
    testSources set { FileSet(projectRoot.get() / "src/test/kotlin") }
    resources set { FileSet(projectRoot.get() / "src/main/resources") }
    testResources set { FileSet(projectRoot.get() / "src/test/resources") }

    libraryDependencies addAll { if (automaticKotlinStdlib.get()) listOf(kotlinDependency("stdlib-js")) else emptyList() }

    compilerOptions[KotlinCompilerFlags.moduleName] = { projectName.get().toValidIdentifier() ?: "myModule" }

    kotlinCompiler set { kotlinVersion.get().compilerInstance(progressListener) }
    compile set KeyDefaults.CompileKotlinJS
}

//endregion

//region Layer archetypes

/** An archetype layer adding support for JUnit testing.
 * Intended to be used with other [JVMBase]-based archetype. */
val JUnitLayer by archetype {
    testParameters set KeyDefaults.TestParameters
    test set KeyDefaults.Test

    // JUnit dependencies are applied only into the testing scope,
    // because adding it globally (even with test scope) breaks the
    // (surprisingly common) case when these libraries are needed
    // in normal, non-testing code as well.
    // The libraries must also be added into the ideImport, because
    // IDE does not (by default) look into configurations.
    val testingDependencies:Value<Iterable<Dependency>> = { listOf(
            Dependency(JUnitAPI, scope = ScopeTest),
            Dependency(JUnitEngine, scope = ScopeTest),
            Dependency(JUnitPlatformLauncher, scope = ScopeTest)
    ) }
    extend(testing) {
        libraryDependencies addAll testingDependencies
    }
    extend(ideImport) {
        libraryDependencies addAll testingDependencies
    }
}

@Deprecated("Use JUnitLayer instead", ReplaceWith("Archetypes.JUnitLayer", "wemi.Archetypes"))
val JUnitProject = JUnitLayer

//endregion
