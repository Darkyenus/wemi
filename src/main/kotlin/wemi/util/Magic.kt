package wemi.util

import org.slf4j.LoggerFactory
import wemi.WemiVersion
import wemi.WemiVersionIsSnapshot
import java.nio.file.Files
import java.nio.file.Path

/**
 * File full of black magic.
 * No muggles allowed.
 */

private val LOG = LoggerFactory.getLogger("Magic")

@Suppress("ClassName")
private object __ResourceHook

internal val WemiLauncherFile: Path = __ResourceHook.javaClass.getResource("MagicKt.class").let {
    var result: Path = it?.toPath() ?: throw IllegalStateException("Wemi must be launched from filesystem (current URL: $it)")

    if (result.name == "MagicKt.class") {
        result = result.parent //./wemi/util
        result = result.parent //./wemi
        result = result.parent //./
    }

    LOG.debug("WemiLauncherFile found at {}", result)
    result
}

private val WemiLauncherFileWithJarExtensionCache = mutableMapOf<Path, Path>()

internal fun wemiLauncherFileWithJarExtension(cacheFolder: Path): Path {
    return WemiLauncherFileWithJarExtensionCache.getOrPut(cacheFolder) lazy@ {
        val wemiLauncherFile = WemiLauncherFile
        if (wemiLauncherFile.name.endsWith(".jar", ignoreCase = true) || wemiLauncherFile.isDirectory) {
            LOG.debug("WemiLauncherFileWithJar is unchanged {}", wemiLauncherFile)
            return@lazy wemiLauncherFile
        }
        // We have create a link to/copy of the launcher file somewhere and name it with .jar

        val name = if (WemiVersionIsSnapshot) {
            val digest = wemiLauncherFile.hash("MD5")
            val sb = StringBuilder()
            sb.append(WemiVersion)
            sb.append('.')
            sb.append(toHexString(digest))
            sb.append(".jar")
            sb.toString()
        } else {
            "wemi-$WemiVersion.jar"
        }

        val wemiLauncherLinksDirectory = cacheFolder / "wemi-launcher-links"
        Files.createDirectories(wemiLauncherLinksDirectory)
        val linked = wemiLauncherLinksDirectory / name

        if (Files.exists(linked) && Files.getLastModifiedTime(wemiLauncherFile) < Files.getLastModifiedTime(linked)) {
            // Already exists and is fresh
            LOG.debug("WemiLauncherFileWithJar is existing {}", linked)
            return@lazy linked
        }

        Files.deleteIfExists(linked)
        try {
            val result = Files.createSymbolicLink(linked, wemiLauncherFile)
            LOG.debug("WemiLauncherFileWithJar is just linked {}", result)
            return@lazy result
        } catch (e: Exception) {
            LOG.warn("Failed to link {} to {}, copying", wemiLauncherFile, linked, e)

            try {
                Files.copy(wemiLauncherFile, linked)
                LOG.debug("WemiLauncherFileWithJar is just copied {}", linked)
                return@lazy linked
            } catch (e: Exception) {
                LOG.warn("Failed to copy {} to {}, returning non-jar file", wemiLauncherFile, linked, e)
                return@lazy wemiLauncherFile
            }
        }
    }
}

internal val WemiDefaultClassLoader: ClassLoader = __ResourceHook::class.java.classLoader