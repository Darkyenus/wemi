@file:Suppress("unused")

package wemi

import Files
import org.slf4j.LoggerFactory
import wemi.boot.WemiCacheFolder
import wemi.collections.WMutableSet
import wemi.collections.toMutable
import wemi.dependency.LocalM2Repository
import wemi.dependency.Repository
import wemi.dependency.ScopeTest
import wemi.util.copyRecursively
import wemi.util.div
import wemi.util.plus
import wemi.util.toPath

/** All default configurations */
object Configurations {

    /** @see Keys.test */
    val testing by configuration("Used when testing") {
        Keys.sources modify { it + Keys.testSources.get() }
        Keys.resources modify { it + Keys.testResources.get() }
        Keys.scopesCompile add { ScopeTest }

        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes-test")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources-test")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers-test")
    }

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

    /**
     * Used when importing project into IDE.
     * Tasks are done on a best effort basis, especially classpath resolution,
     * since incomplete classpath is better than no classpath.
     * Compilation is skipped and [Keys.externalClasspath] does not include [wemi.dependency.ProjectDependency]
     * derived dependencies.
     */
    val ideImport by configuration("Configuration which omits some tasks and makes other tasks tolerate failures, to make IDE import easier") {
        /*
        Rationale:
        Users expect that modifying externalClasspath will just work, but externalClasspath fails on any error
        and it includes project dependencies, which IDEs track differently, so we would have to differentiate them,
        which is hard.

        Similarly, internalClasspath contains resources and compile output, which IDEs handle differently,
        but it may also contain generated classpath any any other stuff users might add by modification,
        and that should be imported properly.
         */
        extend(Archetypes.Base) { // The change must be injected where the classpath is set originally
            Keys.internalClasspath set KeyDefaults.InternalClasspathForIdeImport
            Keys.externalClasspath set KeyDefaults.ExternalClasspathForIdeImport
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
        Keys.publishRepository put Repository("local-jitpack", PublishRepositoryRoot)

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