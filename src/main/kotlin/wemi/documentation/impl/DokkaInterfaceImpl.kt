package wemi.documentation.impl

import org.jetbrains.dokka.DocumentationOptions
import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.DokkaGenerator
import org.jetbrains.dokka.DokkaLogger
import org.slf4j.Logger
import org.slf4j.Marker
import wemi.documentation.DokkaInterface
import wemi.documentation.DokkaOptions
import wemi.util.LocatedFile
import wemi.util.absolutePath
import java.net.URL
import java.nio.file.Path

/**
 * [DokkaInterface] implementation, DO NOT TOUCH FROM ELSEWHERE THAN [wemi.KeyDefaults.Dokka]!!!
 */
@Suppress("unused")
internal class DokkaInterfaceImpl : DokkaInterface {

    override fun execute(sources: Collection<LocatedFile>,
                         classpath: Collection<Path>,
                         outputDirectory:Path,
                         packageListCache:Path?,
                         options:DokkaOptions,
                         logger: Logger,
                         loggerMarker: Marker?) {

        val sourceRoots = HashSet<Path>()
        for (source in sources) {
            sourceRoots.add(source.root)
        }

        val gen = DokkaGenerator(object : DokkaLogger {

            override fun info(message: String) {
                logger.info(loggerMarker, "{}", message)
            }

            override fun warn(message: String) {
                logger.warn(loggerMarker, "{}", message)
            }

            override fun error(message: String) {
                logger.error(loggerMarker, "{}", message)
            }
        },
                classpath.map { it.absolutePath },
                sourceRoots.map { root ->
                    val rootPath = root.absolutePath
                    object : DokkaConfiguration.SourceRoot {
                        override val path: String
                            get() = rootPath
                        override val platforms: List<String>
                            get() = emptyList()

                    }
                },
                options.sampleRoots.map { it.absolutePath },
                options.includes.map { it.absolutePath },
                options.moduleName,
                DocumentationOptions(outputDirectory.absolutePath, options.outputFormat,
                        sourceLinks = options.sourceLinks.map { s ->
                            object : DokkaConfiguration.SourceLinkDefinition {
                                override val lineSuffix: String?
                                    get() = s.urlSuffix
                                override val path: String
                                    get() = s.dir.absolutePath
                                override val url: String
                                    get() = s.url
                            }
                        },
                        jdkVersion = options.jdkVersion,
                        skipDeprecated = options.skipDeprecated,
                        skipEmptyPackages = options.skipEmptyPackages,
                        reportUndocumented = options.reportNotDocumented,
                        impliedPlatforms = options.impliedPlatforms,
                        perPackageOptions = options.perPackageOptions.map { o ->
                            object : DokkaConfiguration.PackageOptions {
                                override val prefix: String
                                    get() = o.prefix
                                override val includeNonPublic: Boolean
                                    get() = o.includeNonPublic
                                override val reportUndocumented: Boolean
                                    get() = o.reportNotDocumented
                                override val skipDeprecated: Boolean
                                    get() = o.skipDeprecated
                            }
                        },
                        externalDocumentationLinks = options.externalDocumentationLinks.map { e ->
                            val docUrl = URL(e.url)
                            val docPackageListUrl:URL
                            if (e.packageListUrl == null) {
                                docPackageListUrl = URL(docUrl, "package-list")
                            } else {
                                docPackageListUrl = URL(e.packageListUrl)
                            }

                            object : DokkaConfiguration.ExternalDocumentationLink {
                                override val packageListUrl: URL
                                    get() = docPackageListUrl
                                override val url: URL
                                    get() = docUrl
                            }
                        },
                        noStdlibLink = options.noStdlibLink,
                        cacheRoot = packageListCache?.absolutePath
                )
        )

        gen.generate()
    }

}