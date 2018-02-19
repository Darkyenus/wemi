package wemi.documentation

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.nio.file.Path

/**
 * Provides access to Dokka, Kotlin documentation tool.
 */
interface DokkaInterface {

    /**
     * @param classpath used when compiling sources
     * @param outputDirectory to put the result in
     * @param packageListCache "Use default or set to custom path to cache directory to enable package-list caching.
     * When set to default, caches stored in $USER_HOME/.cache/dokka
     * @param options other settings
     * @param logger to log info to
     * @param loggerMarker to use when logging with [logger]
     */
    fun execute(classpath: Collection<Path>,
                outputDirectory: Path,
                packageListCache: Path?,
                options: DokkaOptions,
                logger: Logger = LoggerFactory.getLogger("Dokka"),
                loggerMarker: Marker? = null)
}