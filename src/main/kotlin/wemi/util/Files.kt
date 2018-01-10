package wemi.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.URL
import java.net.URLDecoder
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

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

fun URL.toPath(): Path? {
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

    return if (url.protocol == "file") {
        Paths.get(URLDecoder.decode(url.file, Charsets.UTF_8.name()))
    } else {
        null
    }
}

/** Append given path to the file. It is safe to use '/' slashes for directories
 * (this is preferred to chaining [div] calls). Do not use '\' backslash. */
@Suppress("NOTHING_TO_INLINE")
operator inline fun Path.div(path: String): Path = this.resolve(path)

inline val Path.isDirectory:Boolean
        get() = Files.isDirectory(this)

inline val Path.isHidden:Boolean
    get() = Files.isHidden(this)

@Suppress("NOTHING_TO_INLINE")
inline fun Path.exists():Boolean = Files.exists(this)

inline val Path.name:String
    get() = this.fileName?.toString() ?: ""

inline val Path.nameWithoutExtension:String
    get() {
        val name = this.name
        val lastDot = name.lastIndexOf('.')
        return if (lastDot == -1) {
            name
        } else {
            name.substring(0, lastDot)
        }
    }

inline val Path.absolutePath:String
    get() = this.toAbsolutePath().toString()

inline var Path.lastModified:FileTime
    get() = Files.getLastModifiedTime(this)
    set(value) { Files.setLastModifiedTime(this, value) }

inline val Path.size:Long
    get() = Files.size(this)

inline fun Path.forEachLine(action: (String) -> Unit) {
    for (line in Files.lines(this, Charsets.UTF_8)) {
        action(line)
    }
}

fun Path.writeText(text:CharSequence) {
    OutputStreamWriter(Files.newOutputStream(this)).use {
        it.append(text)
    }
}

fun Path.writeBytes(bytes:ByteArray, offset:Int = 0, length:Int = bytes.size) {
    Files.newOutputStream(this).use {
        it.write(bytes, offset, length)
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun Path.readBytes():ByteArray {
    return Files.readAllBytes(this)
}

inline fun Path.forChildren(action:(Path) -> Unit) {
    Files.newDirectoryStream(this).use { stream ->
        for (path in stream) {
            action(path)
        }
    }
}

private val DELETING_FILE_VISITOR = object : FileVisitor<Path> {

    private val LOG: Logger = LoggerFactory.getLogger("Files.deleteRecursively".javaClass)

    override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes?): FileVisitResult {
        return FileVisitResult.CONTINUE
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!attrs.isDirectory) {
            Files.delete(file)
        }
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
        if (exc != null) {
            LOG.warn("Premature postVisitDirectory in {}", dir, exc)
        }

        Files.delete(dir)
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
        LOG.warn("Failed to visit file {}", file, exc)
        return FileVisitResult.CONTINUE
    }
}

fun Path.deleteRecursively() {
    Files.walkFileTree(this, DELETING_FILE_VISITOR)
}