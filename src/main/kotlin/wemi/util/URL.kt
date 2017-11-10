package wemi.util

import java.io.File
import java.net.URL
import java.net.URLDecoder

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
    if (host != null
            && !host.isBlank()
            && !host.equals("localhost", ignoreCase = true)
            && !host.equals("127.0.0.1", ignoreCase = true)) {
        return null
    }

    var url = this

    if (url.protocol == "jar") {
        url = URL(url.file.substring(0, url.file.lastIndexOf("!/")))
    }

    if (url.protocol == "file") {
        return File(URLDecoder.decode(url.file, Charsets.UTF_8.name()))
    } else {
        return null
    }
}

/** Append given path to the file. It is safe to use '/' slashes for directories
 * (this is preferred to chaining [div] calls). Do not use '\' backslash. */
@Suppress("NOTHING_TO_INLINE")
operator inline fun File.div(path: String): File {
    return File(this, path)
}