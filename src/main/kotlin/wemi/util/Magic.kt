package wemi.util

import org.slf4j.LoggerFactory
import java.nio.file.Path

/*
 * File full of black magic.
 * No muggles allowed.
 */
object Magic {

    private val LOG = LoggerFactory.getLogger("Magic")

    /**
     * Returns the classpath file, from which given class has been loaded.
     * This may be a jar file, or a directory in which it resides.
     */
    fun classpathFileOf(c:Class<*>):Path? {
        val name = c.name
        val classNameStart = name.lastIndexOf('.')
        val resourceName = "${if (classNameStart == -1) name else name.substring(classNameStart + 1)}.class"
        var path:Path = c.getResource(resourceName)?.toPath() ?: return null

        if (path.name == resourceName) {
            // Not a jar, but a file inside directory
            path = path.parent ?: return null // To directory
            if (classNameStart != -1) {
                // Amount of dots (package separators) is the same as the amount of steps to parent
                for (i in 0..classNameStart) {
                    if (name[i] == '.') {
                        path = path.parent ?: return null
                    }
                }
            } // else in default directory, so the path is correct
        }

        return path
    }

    /**
     * .jar or folder that is a classpath entry that contains Wemi
     */
    internal val WemiLauncherFile: Path = classpathFileOf(this.javaClass).let {
        val result = it ?: throw IllegalStateException("Wemi must be launched from filesystem (current URL: $it)")

        LOG.debug("WemiLauncherFile found at {}", result)
        result
    }

    /**
     * Class loader with which Wemi core was loaded
     */
    internal val WemiDefaultClassLoader: ClassLoader = javaClass.classLoader

    /**
     * Transform the [fileNameWithoutExtension] into the name of class that will Kotlin compiler produce (without .class).
     */
    internal fun transformFileNameToKotlinClassName(fileNameWithoutExtension: String): String {
        val sb = StringBuilder()
        // If file name starts with digit, _ is prepended
        if (fileNameWithoutExtension.isNotEmpty() && fileNameWithoutExtension[0] in '0'..'9') {
            sb.append('_')
        }
        // Everything is valid java identifier
        for (c in fileNameWithoutExtension) {
            if (c.isJavaIdentifierPart()) {
                sb.append(c)
            } else {
                sb.append("_")
            }
        }
        // First letter is capitalized
        if (sb.isNotEmpty()) {
            sb[0] = sb[0].toUpperCase()
        }
        // Kt is appended
        sb.append("Kt")
        return sb.toString()
    }
}