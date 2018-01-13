@file:Suppress("unused")

package wemi

import configuration
import wemi.compile.*
import wemi.dependency.Dependency
import wemi.dependency.Repository.M2.Companion.M2ClassifierAttribute
import wemi.test.JUnitPlatformLauncher
import wemi.util.div
import javax.tools.ToolProvider

/**
 * All default configurations
 */
object Configurations {

    //region Stage configurations
    val compiling by configuration("Configuration used when compiling") {}

    val running by configuration("Configuration used when running, sources are resources") {}
    //endregion

    //region Testing configuration
    val testing by configuration("Used when testing") {
        Keys.sourceBases add { Keys.projectRoot.get() / "src/test" }
        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes-test")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources-test")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers-test")

        Keys.libraryDependencies add { Dependency(JUnitPlatformLauncher) }
    }
    //endregion

    //region IDE configurations
    val retrievingSources by configuration("Used to retrieve sources") {
        Keys.libraryDependencyProjectMapper set {
            { (projectId, exclusions): Dependency ->
                val sourcesProjectId = projectId.copy(attributes = projectId.attributes + (M2ClassifierAttribute to "sources"))
                Dependency(sourcesProjectId, exclusions)
            }
        }
    }

    val retrievingDocs by configuration("Used to retrieve docs") {
        Keys.libraryDependencyProjectMapper set {
            { (projectId, exclusions): Dependency ->
                val javadocProjectId = projectId.copy(attributes = projectId.attributes + (M2ClassifierAttribute to "javadoc"))
                Dependency(javadocProjectId, exclusions)
            }
        }
    }
    //endregion

    val offline by configuration("Disables non-local repositories from the repository chain for offline use") {
        Keys.repositoryChain modify { oldChain ->
            oldChain.filter { it.local }
        }
    }

    val compilingJava by configuration("Configuration layer used when compiling Java sources", compiling) {
        Keys.sourceRoots set KeyDefaults.SourceRootsJavaKotlin
        Keys.sourceExtensions set { JavaSourceFileExtensions }
        Keys.javaCompiler set { ToolProvider.getSystemJavaCompiler() }
        Keys.compilerOptions[JavaCompilerFlags.customFlags] += "-g"
        Keys.compilerOptions[JavaCompilerFlags.sourceVersion] = JavaVersion.V1_8
        Keys.compilerOptions[JavaCompilerFlags.targetVersion] = JavaVersion.V1_8
    }

    val compilingKotlin by configuration("Configuration layer used when compiling Kotlin sources", compiling) {
        Keys.sourceRoots set KeyDefaults.SourceRootsJavaKotlin
        Keys.sourceExtensions set { KotlinSourceFileExtensions }
        Keys.kotlinCompiler set { kotlinCompiler(WemiKotlinVersion) }
    }
}