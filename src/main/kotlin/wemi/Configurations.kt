@file:Suppress("unused")

package wemi

import Files
import org.slf4j.LoggerFactory
import wemi.KeyDefaults.classifierAppendingClasspathModifier
import wemi.KeyDefaults.classifierAppendingLibraryDependencyProjectMapper
import wemi.KeyDefaults.inProjectDependencies
import wemi.boot.WemiCacheFolder
import wemi.collections.WMutableSet
import wemi.collections.toMutable
import wemi.dependency.JavadocClassifier
import wemi.dependency.LocalM2Repository
import wemi.dependency.Repository
import wemi.dependency.ScopeCompile
import wemi.dependency.ScopeProvided
import wemi.dependency.ScopeRuntime
import wemi.dependency.ScopeTest
import wemi.dependency.SourcesClassifier
import wemi.util.copyRecursively
import wemi.util.div
import wemi.util.plus
import wemi.util.toPath

/** All default configurations */
object Configurations {

    //region Stage configurations
    val stageAxis by configuration("Base for stage configurations") {}

    /** @see Keys.compile */
    val compiling by configuration("Configuration used when compiling", stageAxis) {
        Keys.resolvedLibraryScopes addAll { listOf(ScopeCompile, ScopeProvided) }

        Keys.externalClasspath modify { classpath ->
            // Internal classpath of aggregate projects is not included in standard external classpath.
            // But it is needed for the compilation, so we add it explicitly.

            val result = classpath.toMutable()
            inProjectDependencies(true, Keys.resolvedLibraryScopes.get()) {
                result.addAll(Keys.internalClasspath.get())
            }
            result
        }
    }

    /** @see Keys.run */
    val running by configuration("Configuration used when running, sources are resources", stageAxis) {
        Keys.resolvedLibraryScopes addAll { listOf(ScopeCompile, ScopeRuntime) }
    }

    /** @see Keys.assembly */
    val assembling by configuration("Configuration used when assembling Jar with dependencies", stageAxis) {
        Keys.resolvedLibraryScopes addAll { listOf(ScopeCompile, ScopeRuntime) }
    }

    /** Used by [Keys.archive] */
    val archiving by configuration("Used when archiving", stageAxis) {
        Keys.resolvedLibraryScopes addAll { listOf(ScopeCompile, ScopeRuntime) }
    }
    //endregion

    //region Testing
    /** @see Keys.test */
    val testing by configuration("Used when testing") {
        Keys.sources modify { it + Keys.testSources.get() }
        Keys.resources modify { it + Keys.testResources.get() }

        // Testing classpath indeed contains all of these
        // (It is needed for example when there are two dependencies, one with provided scope, another with test scope.
        //  Combined, they have the provided scope, which therefore must be available on the test classpath.)
        Keys.resolvedLibraryScopes addAll { listOf(ScopeCompile, ScopeRuntime, ScopeProvided, ScopeTest) }

        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes-test")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources-test")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers-test")
    }
    //endregion

    //region IDE configurations
    val ideAxis by configuration("Used when called from IDE") {}

    val retrievingSources by configuration("Used to retrieve sources", ideAxis) {
        val mapper = classifierAppendingLibraryDependencyProjectMapper(SourcesClassifier)
        Keys.libraryDependencies modify { it.map(mapper).toSet() }
        Keys.libraryDependencyMapper set Static(mapper)
        Keys.unmanagedDependencies modify classifierAppendingClasspathModifier(SourcesClassifier)
        Keys.repositories modify KeyDefaults.MakeAllRepositoriesAuthoritative
    }

    val retrievingDocs by configuration("Used to retrieve documentation", ideAxis) {
        val mapper = classifierAppendingLibraryDependencyProjectMapper(JavadocClassifier)
        Keys.libraryDependencies modify { it.map(mapper).toSet() }
        Keys.libraryDependencyMapper set Static(mapper)
        Keys.unmanagedDependencies modify classifierAppendingClasspathModifier(JavadocClassifier)
        Keys.repositories modify KeyDefaults.MakeAllRepositoriesAuthoritative
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
                    } else if (repository.cache != null) {
                        add(Repository(repository.name + "-cache", repository.cache,
                                releases = repository.releases,
                                snapshots = repository.snapshots,
                                snapshotUpdateDelaySeconds = repository.snapshotUpdateDelaySeconds,
                                tolerateChecksumMismatch = repository.tolerateChecksumMismatch))
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

    /** To be used when publishing on [jitpack.io](https://jitpack.io). For full build setup, create `jitpack.yml`
     * in the project's root with following content:
     * ```yml
     * jdk:
     * - oraclejdk8
     * install:
     * - java -jar wemi "<project>/jitpack:publish"
     * ```
     * For more information, see [Jitpack documentation](https://jitpack.io/docs/BUILDING/) */
    val jitpack by configuration("Used when building for Jitpack.io") {
        val LOG = LoggerFactory.getLogger("jitpack")

        // For clarity, use the version Jitpack expects
        Keys.projectVersion modify { System.getenv("VERSION") ?: it }

        // Jitpack needs to publish to somewhere within project's repository and to copy it to local maven as well
        val PublishRepositoryRoot = WemiCacheFolder / "-jitpack-out"
        Keys.publishRepository set { Repository("local-jitpack", PublishRepositoryRoot) }

        // Everything which is published inside PublishRepositoryRoot (and we expect to be the case always)
        // should also get copied over to ~/.m2/repository, so that Jitpack can detect it
        Keys.publish modify { publishedResult ->
            if (!publishedResult.startsWith(PublishRepositoryRoot)) {
                LOG.warn("Expected published artifacts to land inside {}, but this is not the case for {}", PublishRepositoryRoot, publishedResult)
                return@modify publishedResult
            }

            val mavenRoot = LocalM2Repository.url.toPath() ?: throw AssertionError("Expected LocalM2Repository url to be local")
            val relative = PublishRepositoryRoot.relativize(publishedResult)
            val mavenResult = mavenRoot.resolve(relative)
            Files.createDirectories(mavenResult)
            publishedResult.copyRecursively(mavenResult)
            LOG.info("{} copied to {}", publishedResult, mavenResult)

            publishedResult
        }

    }
}