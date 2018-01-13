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
import java.nio.file.attribute.PosixFilePermission

/**
 * Creates an URL with given path appended.
 */
operator fun URL.div(path: CharSequence): URL {
    return URL(this, path.toString())
}

/**
 * Appends given path to the receiver path.
 *
 * Makes best effort to modify the receiver CharSequence, i.e.first it checks if it is a StringBuilder that can be used.
 *
 * @return this + '/' + [path], possibly in modified receiver this
 */
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

/**
 * Attempts to convert the URL to a file in a local file system.
 *
 * If the URL points into a file inside jar archive, Path of the archive is returned.
 *
 * If the conversion fails, returns null.
 */
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

/** @see [Files.isDirectory] */
@Suppress("NOTHING_TO_INLINE")
inline fun Path.isDirectory(): Boolean = Files.isDirectory(this)

/** @see [Files.exists] */
@Suppress("NOTHING_TO_INLINE")
inline fun Path.isHidden(): Boolean = Files.isHidden(this)

/** @see [Files.exists] */
@Suppress("NOTHING_TO_INLINE")
inline fun Path.exists(): Boolean = Files.exists(this)

/** @return name of the file, without the rest of the path, with extension */
inline val Path.name: String
    get() = this.fileName?.toString() ?: ""

/**
 * Retrieve the extension from the given file path.
 * Returns empty string when the file has no extension.
 */
fun String.pathExtension():String {
    val lastDot = lastIndexOf('.')
    val lastSlash = lastIndexOf('/')
    return if (lastDot == -1 || lastDot < lastSlash) {
        ""
    } else {
        substring(lastDot + 1)
    }
}

/**
 * Retrieve the file path without the file' extension.
 * Returns this string when the file has no extension.
 */
fun String.pathWithoutExtension():String {
    val lastDot = lastIndexOf('.')
    val lastSlash = lastIndexOf('/')
    return if (lastDot == -1 || lastDot < lastSlash) {
        this
    } else {
        this.substring(0, lastDot)
    }
}

/**
 * Return true if this file path has specified extension.
 *
 * Not case sensitive.
 */
fun String.pathHasExtension(extension: String):Boolean {
    val length = this.length
    if (length >= extension.length + 1
            && this.endsWith(extension, ignoreCase = true)
            && this[length - extension.length - 1] == '.') {
        return true
    }
    return false
}

/**
 * Return true if this file path has any of the specified extensions.
 *
 * Not case sensitive.
 */
fun String.pathHasExtension(extensions: Iterable<String>): Boolean {
    val name = this
    val length = name.length
    for (extension in extensions) {
        if (length >= extension.length + 1
                && name.endsWith(extension, ignoreCase = true)
                && name[length - extension.length - 1] == '.') {
            return true
        }
    }
    return false
}

/**
 * @return absolute path to this Path
 */
inline val Path.absolutePath: String
    get() = this.toAbsolutePath().toString()

/**
 * Last modified time of the file denoted by the [Path], as specified by the filesystem.
 *
 * @see Files.getLastModifiedTime
 */
inline var Path.lastModified: FileTime
    get() = Files.getLastModifiedTime(this)
    set(value) {
        Files.setLastModifiedTime(this, value)
    }

/**
 * Size of the file on disk.
 *
 * @see [Files.size]
 */
inline val Path.size: Long
    get() = Files.size(this)

/**
 * Reads the Path, line by line, and calls action for each line.
 */
inline fun Path.forEachLine(action: (String) -> Unit) {
    Files.newBufferedReader(this, Charsets.UTF_8).use { br ->
        while (true) {
            val line = br.readLine() ?: break
            action(line)
        }
    }
}

/**
 * Path's POSIX executable flag status.
 * Path is considered executable when at least one of Owner:Group:Others has executable bit set.
 * When setting executable, all three bits are set/cleared.
 */
@Suppress("unused")
var Path.executable: Boolean
    get() {
        val permissions = Files.getPosixFilePermissions(this)
        return permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
                || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                || permissions.contains(PosixFilePermission.OWNER_EXECUTE)
    }
    set(value) {
        val permissions = Files.getPosixFilePermissions(this).toMutableSet()
        if (value) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            permissions.add(PosixFilePermission.OTHERS_EXECUTE)
        } else {
            permissions.remove(PosixFilePermission.OWNER_EXECUTE)
            permissions.remove(PosixFilePermission.GROUP_EXECUTE)
            permissions.remove(PosixFilePermission.OTHERS_EXECUTE)
        }
        Files.setPosixFilePermissions(this, permissions)
    }

/**
 * Write given text into this path.
 *
 * File will be overwritten.
 *
 * Parent directory must exist.
 */
fun Path.writeText(text: CharSequence) {
    OutputStreamWriter(Files.newOutputStream(this)).use {
        it.append(text)
    }
}

/**
 * Used by [deleteRecursively]
 */
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

/**
 * Delete this file.
 *
 * Does nothing when the file does not exist, deletes children files if the path is a non-empty directory.
 */
fun Path.deleteRecursively() {
    if (!Files.exists(this)) {
        return
    }
    if (Files.isDirectory(this)) {
        Files.walkFileTree(this, DELETING_FILE_VISITOR)
    } else {
        Files.delete(this)
    }
}