package wemi.util

import java.io.File
import java.net.URL

/**
 * Custom URL implementation
 */

operator fun URL.div(path: CharSequence): URL {
    return URL(this, path.toString())
}

operator fun CharSequence.div(path: CharSequence): StringBuilder {
    val sb: StringBuilder

    if (this is StringBuilder) {
        sb = this
        sb.ensureCapacity(path.length + 1)
    } else {
        sb = StringBuilder(this.length + 1 + path.length)
        sb.append(this)
    }
    sb.append('/')
    sb.append(path)

    return sb
}

fun URL.toFile(): File? {
    when (protocol) {
        "file" -> {
            if (host.isNullOrBlank() || host == "localhost") {
                return File(path)
            } else {
                return null
            }
        }
        "jar" -> {
            if (path.startsWith("file:")) {
                val begin = path.indexOf(':')
                val end = path.lastIndexOf('!')
                return File(path.substring(begin, if (end == -1) path.length else end))
            } else {
                return null
            }
        }
        else -> {
            return null
        }
    }
}

/** Append given path to the file. It is safe to use '/' slashes for directories
 * (this is preferred to chaining [div] calls). Do not use '\' backslash. */
@Suppress("NOTHING_TO_INLINE")
operator inline fun File.div(path: String): File {
    return File(this, path)
}