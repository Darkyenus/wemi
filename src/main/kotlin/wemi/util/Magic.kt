package wemi.util

import org.slf4j.LoggerFactory
import java.nio.file.Path

/*
 * File full of black magic.
 * No muggles allowed.
 */

private val LOG = LoggerFactory.getLogger("Magic")

/**
 * Only used as a known and stable place to start magic from.
 */
@Suppress("ClassName")
private class __ResourceHook

/**
 * .jar or folder that is a classpath entry that contains Wemi
 */
internal val WemiLauncherFile: Path = __ResourceHook::class.java.getResource("MagicKt.class").let {
    var result: Path = it?.toPath() ?: throw IllegalStateException("Wemi must be launched from filesystem (current URL: $it)")

    if (result.name == "MagicKt.class") {
        result = result.parent //./wemi/util
        result = result.parent //./wemi
        result = result.parent //./
    }

    LOG.debug("WemiLauncherFile found at {}", result)
    result
}

internal val WemiDefaultClassLoader: ClassLoader = __ResourceHook::class.java.classLoader