package wemi.util

import org.slf4j.LoggerFactory
import java.io.File

/**
 * File full of black magic.
 * No muggles allowed.
 */

private val LOG = LoggerFactory.getLogger("Magic")

private object __ResourceHook

internal val WemiClasspathFile: File = __ResourceHook.javaClass.getResource("MagicKt.class").let { dotResource ->
    val result: File?
    if (dotResource.protocol == "file") {
        result = File(dotResource.path.removeSuffix("wemi/boot/MagicKt.class"))
    } else {
        result = dotResource.toFile()
    }
    if (result == null) {
        throw IllegalStateException("Wemi must be launched from filesystem (current URL: $dotResource)")
    }
    LOG.debug("WemiClasspathFile found at {}", result)
    result
}

internal val WemiDefaultClassLoader : ClassLoader = __ResourceHook::class.java.classLoader