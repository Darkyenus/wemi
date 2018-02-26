@file:Suppress("unused")

package wemi

import configuration
import wemi.KeyDefaults.ArchiveDummyDocumentation
import wemi.KeyDefaults.classifierAppendingLibraryDependencyProjectMapper
import wemi.collections.toWList
import wemi.collections.wEmptyList
import wemi.collections.wListOf
import wemi.compile.*
import wemi.dependency.Dependency
import wemi.dependency.Repository.M2.Companion.JavadocClassifier
import wemi.dependency.Repository.M2.Companion.SourcesClassifier
import wemi.test.JUnitPlatformLauncher
import wemi.util.*

/**
 * All default configurations
 */
object Configurations {

    //region Stage configurations
    val compiling by configuration("Configuration used when compiling") {}

    val running by configuration("Configuration used when running, sources are resources") {}

    val assembling by configuration("Configuration used when assembling Jar with dependencies") {}
    //endregion

    //region Compiling
    val compilingJava by configuration("Configuration layer used when compiling Java sources", compiling) {
        Keys.sourceRoots set KeyDefaults.SourceRootsJavaKotlin
        Keys.sourceExtensions set { JavaSourceFileExtensions }
        Keys.sourceFiles set KeyDefaults.SourceFiles

        Keys.compilerOptions[JavaCompilerFlags.customFlags] += "-g"
        Keys.compilerOptions[JavaCompilerFlags.sourceVersion] = JavaVersion.V1_8
        Keys.compilerOptions[JavaCompilerFlags.targetVersion] = JavaVersion.V1_8
    }

    val compilingKotlin by configuration("Configuration layer used when compiling Kotlin sources", compiling) {
        Keys.sourceRoots set KeyDefaults.SourceRootsJavaKotlin
        Keys.sourceExtensions set { KotlinSourceFileExtensions }
        Keys.sourceFiles set KeyDefaults.SourceFiles

        Keys.kotlinCompiler set { Keys.kotlinVersion.get().compilerInstance() }
        Keys.compilerOptions modify {
            it.apply {
                set(KotlinCompilerFlags.moduleName, Keys.projectName.get())
            }
        }
        Keys.compilerOptions[KotlinJVMCompilerFlags.jvmTarget] = "1.8"
    }
    //endregion

    //region Testing configuration
    /**
     * Used by [Keys.test]
     */
    val testing by configuration("Used when testing") {
        Keys.sourceBases add { Keys.projectRoot.get() / "src/test" }
        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes-test")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources-test")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers-test")

        Keys.libraryDependencies add { Dependency(JUnitPlatformLauncher) }
    }
    //endregion

    //region Archiving
    /**
     * Used by [Keys.archive]
     */
    val archiving by configuration("Used when archiving") {}

    val archivingSources by configuration("Used when archiving sources") {
        Keys.archiveOutputFile modify { original ->
            val originalName = original.name
            val withoutExtension = originalName.pathWithoutExtension()
            val extension = originalName.pathExtension()
            original.resolveSibling("$withoutExtension-sources.$extension")
        }
        Keys.archive set KeyDefaults.ArchiveSources
    }

    val archivingDocs by configuration("Used when archiving documentation") {
        Keys.archiveOutputFile modify { original ->
            val originalName = original.name
            val withoutExtension = originalName.pathWithoutExtension()
            val extension = originalName.pathExtension()
            original.resolveSibling("$withoutExtension-docs.$extension")
        }
        // Dummy archival
        Keys.archive set ArchiveDummyDocumentation
    }
    //endregion

    //region Publishing
    val publishing by configuration("Used when publishing archived outputs") {}
    //endregion

    //region IDE configurations
    val retrievingSources by configuration("Used to retrieve sources") {
        val mapper = classifierAppendingLibraryDependencyProjectMapper(SourcesClassifier)
        Keys.libraryDependencyProjectMapper set { mapper }
    }

    val retrievingDocs by configuration("Used to retrieve documentation") {
        val mapper = classifierAppendingLibraryDependencyProjectMapper(JavadocClassifier)
        Keys.libraryDependencyProjectMapper set { mapper }
    }
    //endregion

    val offline by configuration("Disables non-local repositories from the repository chain for offline use") {
        Keys.repositoryChain modify { oldChain ->
            oldChain.filter { it.local }
        }
    }

    val wemiBuildScript by configuration("Setup with information about the build script " +
            "(but not actually used for building the build script)") {
        Keys.repositories set { Keys.buildScript.get().buildScriptClasspathConfiguration.repositories }
        Keys.repositoryChain set { Keys.buildScript.get().buildScriptClasspathConfiguration.repositoryChain }
        Keys.libraryDependencies set { Keys.buildScript.get().buildScriptClasspathConfiguration.dependencies }
        Keys.externalClasspath set { Keys.buildScript.get().classpath.map { LocatedFile(it) }.toWList() }
        Keys.compilerOptions set { Keys.buildScript.get().buildFlags }
        Keys.compile set { Keys.buildScript.get().scriptJar }
        Keys.resourceFiles set { wEmptyList() }
        Keys.sourceFiles set { Keys.buildScript.get().sources }
    }
}