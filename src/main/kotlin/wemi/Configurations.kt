@file:Suppress("unused")

package wemi

import configuration
import wemi.KeyDefaults.ArchiveDummyDocumentation
import wemi.KeyDefaults.classifierAppendingClasspathModifier
import wemi.KeyDefaults.classifierAppendingLibraryDependencyProjectMapper
import wemi.KeyDefaults.inProjectDependencies
import wemi.collections.WMutableSet
import wemi.collections.toMutable
import wemi.compile.*
import wemi.dependency.Dependency
import wemi.dependency.MavenRepository.Companion.JavadocClassifier
import wemi.dependency.MavenRepository.Companion.SourcesClassifier
import wemi.dependency.Repository
import wemi.test.JUnitPlatformLauncher
import wemi.util.*

/** All default configurations */
object Configurations {

    //region Stage configurations
    /** @see Keys.compile */
    val compiling by configuration("Configuration used when compiling") {
        Keys.externalClasspath modify { classpath ->
            // Internal classpath of aggregate projects is not included in standard external classpath.
            // But it is needed for the compilation, so we add it explicitly.

            val result = classpath.toMutable()
            inProjectDependencies(true) {
                result.addAll(Keys.internalClasspath.get())
            }
            result
        }
    }

    /** @see Keys.run */
    val running by configuration("Configuration used when running, sources are resources") {}

    /** @see Keys.test */
    val testing by configuration("Used when testing") {
        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes-test")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources-test")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers-test")

        Keys.libraryDependencies add { Dependency(JUnitPlatformLauncher) }
    }

    /** @see Keys.assembly */
    val assembling by configuration("Configuration used when assembling Jar with dependencies") {}
    //endregion

    //region Compiling
    val compilingJava by configuration("Configuration layer used when compiling Java sources", compiling) {
        Keys.sources modify {
            it.appendPatterns(filter(*Array(JavaSourceFileExtensions.size) { i -> "**.${JavaSourceFileExtensions[i]}"}))
        }

        Keys.compilerOptions[JavaCompilerFlags.customFlags] += "-g"
        Keys.compilerOptions[JavaCompilerFlags.sourceVersion] = JavaVersion.V1_8
        Keys.compilerOptions[JavaCompilerFlags.targetVersion] = JavaVersion.V1_8
    }

    val compilingKotlin by configuration("Configuration layer used when compiling Kotlin sources", compiling) {
        Keys.sources modify {
            it.appendPatterns(filter(*Array(KotlinSourceFileExtensions.size) { i ->"**.${KotlinSourceFileExtensions[i]}"}))
        }

        Keys.kotlinCompiler set { Keys.kotlinVersion.get().compilerInstance() }
        Keys.compilerOptions modify {
            it.apply {
                set(KotlinCompilerFlags.moduleName, Keys.projectName.get())
            }
        }
        Keys.compilerOptions[KotlinJVMCompilerFlags.jvmTarget] = "1.8"
    }
    //endregion

    //region Archiving
    /** Used by [Keys.archive] */
    val archiving by configuration("Used when archiving") {}

    /** Use this configuration to obtain sources archived in [Keys.archive]. */
    val archivingSources by configuration("Used when archiving sources") {
        Keys.archiveOutputFile modify { original ->
            val originalName = original.name
            val withoutExtension = originalName.pathWithoutExtension()
            val extension = originalName.pathExtension()
            original.resolveSibling("$withoutExtension-sources.$extension")
        }
        Keys.archive set KeyDefaults.ArchiveSources
    }

    /** Use this configuration to obtain documentation archived in [Keys.archive]. */
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
    val publishing by configuration("Used when publishing archived outputs") {
        Keys.archive set KeyDefaults.ArchivePublishing
    }
    //endregion

    //region IDE configurations
    val retrievingSources by configuration("Used to retrieve sources") {
        Keys.libraryDependencyProjectMapper set Static(classifierAppendingLibraryDependencyProjectMapper(SourcesClassifier))
        Keys.unmanagedDependencies modify classifierAppendingClasspathModifier(SourcesClassifier)
    }

    val retrievingDocs by configuration("Used to retrieve documentation") {
        Keys.libraryDependencyProjectMapper set Static(classifierAppendingLibraryDependencyProjectMapper(JavadocClassifier))
        Keys.unmanagedDependencies modify classifierAppendingClasspathModifier(JavadocClassifier)
    }
    //endregion

    /**
     * Attempts to disable all features that would fail while offline.
     * Most features will rely on caches to do internet dependent things,
     * so if you don't have caches built, some operations may fail.
     *
     * Note: as this disables some important features, do not use it for production releases.
     */
    val offline by configuration("Disables features that are not available when offline") {
        // Disable non-local repositories
        Keys.repositories modify { oldChain ->
            WMutableSet<Repository>().apply {
                for (repository in oldChain) {
                    if (repository.local) {
                        add(repository)
                    }
                }
            }
        }

        // Remove external documentation links if they don't have explicit package and don't point to 'file:' url
        fun String.localUrl():Boolean {
            return this.startsWith("file:", ignoreCase = true)
        }

        Keys.archiveDokkaOptions modify { options ->
            // TODO This sadly does not work as org.jetbrains.dokka.DocumentationOptions adds own links.
            // However, Dokka caches package-lists, so it should work after you package once and cache gets created.
            options.apply {
                externalDocumentationLinks.removeIf {
                    if (it.packageListUrl != null) {
                        false
                    } else !it.url.localUrl()
                }
            }
        }

        Keys.archiveJavadocOptions modify {
            // Search for -link options and remove them if they are not local
            // There is also -linkoffline option, but that specifies explicit package-list, so it should be fine
            it.toMutable().also { options ->
                var i = 0
                while (i < options.size - 1) {
                    if (options[i] == "-link" && !options[i+1].localUrl()) {
                        // Delete both link and url, do not move active index
                        options.removeAt(i+1)
                        options.removeAt(i)
                    } else {
                        i++
                    }
                }
            }
        }
    }

    /**
     * To be used when developing something and debugger needs to be attached to the forked process.
     * Enables JDWP in server mode, suspended, on port 5005, by adding relevant flags to the [Keys.runOptions].
     * Any JDWP related flags already present are removed.
     */
    val debug by configuration("Enables JVM debug on any forked process") {
        Keys.runOptions modify { oldOptions ->
            val options = oldOptions.toMutable()
            options.removeIf { it.startsWith("-agentlib:jdwp=") }
            options.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
            options
        }
    }
}