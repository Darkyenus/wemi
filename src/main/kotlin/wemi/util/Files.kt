@file:Suppress("unused", "NOTHING_TO_INLINE")

package wemi.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.*
import java.util.concurrent.Semaphore

private val LOG = LoggerFactory.getLogger("Files")

/** Create a new [URL] with [extraPath] appended to the [URL.path].
 * Useful for adding an extension to the file [URL] points to. */
internal fun URL.appendToPath(extraPath:CharSequence):URL {
    if (extraPath.isEmpty()) {
        return this
    }

    val oldPath = this.path ?: ""
    val query = this.query ?: ""
    val fragment:String? = this.ref

    val newFile = StringBuilder(oldPath.length + query.length + extraPath.length + (fragment?.length ?: 0) + 2)
    newFile.append(oldPath).append(extraPath)

    if (!query.isEmpty()) {
        newFile.append('?').append(query)
    }

    if (fragment != null) {
        newFile.append('#').append(fragment)
    }
    return URL(protocol, host, port, newFile.toString())
}

/** Creates an URL with given path appended, using logic similar to the one used in [pathAppend], with the addition that
 * the path may end with `?<query>` and `#<fragment>` parts, (in this order),
 * in which case the query is added to the existing query and fragment replaces existing fragment.  */
operator fun URL.div(path: CharSequence): URL {
    val oldPath = this.path ?: ""
    val query = this.query ?: ""
    var extraQuery:String? = null
    var fragment:String? = this.ref

    val newFile = StringBuilder(oldPath.length + query.length + (fragment?.length ?: 0) + path.length + 16)
    var pathEnd = path.length

    val fragmentStart = path.indexOf('#')
    if (fragmentStart in 0 until pathEnd) {
        fragment = path.substring(fragmentStart + 1)
        pathEnd = fragmentStart
    }

    val queryStart = path.indexOf('?')
    if (queryStart in 0 until pathEnd) {
        extraQuery = path.substring(queryStart + 1, pathEnd)
        pathEnd = queryStart
    }

    if (oldPath.isEmpty()) {
        newFile.append('/')
    } else {
        newFile.append(oldPath)
    }
    newFile.pathAppend(path, 0, pathEnd)

    var hadQuery = false
    if (!query.isEmpty()) {
        newFile.append('?').append(query)
        hadQuery = true
    }
    if (!extraQuery.isNullOrEmpty()) {
        newFile.append(if (hadQuery) '&' else '?').append(extraQuery)
    }

    if (fragment != null) {
        newFile.append('#').append(fragment)
    }
    return URL(protocol, host, port, newFile.toString())
}
/** Treating receiver as a filesystem path, remove the last file name, to get the path of its lexical parent. */
fun String.pathParent():String {
    val path = this
    val searchStart:Int =
            if (path.endsWith('/')) {
                path.lastIndex - 1
            } else {
                path.lastIndex
            }
    val dividingSlash = path.lastIndexOf('/', searchStart)
    return if (dividingSlash <= 0) "" else path.substring(0, dividingSlash)
}

/** Remove all trailing `/`, `/.` and `/..` of this path.
 * Retains one `/` if path is absolute.
 * @return true if path is now empty ("" or "/") */
private fun StringBuilder.pathNormalizeEnd(extraDropLevels:Int) {
    var len = this.length
    val absolute = if (len == 0) false else this[0] == '/'
    var dropLevels = extraDropLevels

    while (true) {
        // Drop "//.///../../." salad, but no legitimate file levels
        do {
            // Trim trailing / (even if it is absolute)
            while (len > 0 && this[len - 1] == '/') {
                len--
            }

            // Check for trailing . and ..
            if (len > 0 && this[len - 1] == '.') {
                // This could be a . or .. situation
                if (len == 1) {
                    // "." situation
                    len--
                    continue
                }
                val beforeDot = this[len - 2]

                if (beforeDot == '/') {
                    // "/." situation
                    len--
                    continue
                }

                // ".." path is valid, we do not want to break that
                if (beforeDot == '.' && len >= 3 && this[len - 3] == '/') {
                    // "anything/.." situation

                    len -= 3 // "/..".length
                    // Now drop next element, simplifying the path
                    dropLevels++
                    continue
                }
            }

            // Nothing requested to continue, therefore break
            break
        } while (len > 0)
        // Salad dropped, now evaluate dropLevels

        if (dropLevels <= 0 || len == 0) {
            // No more levels to drop and end is normalized (or there is nothing to drop anymore), done
            break
        } else {
            // Drop single normal file name
            while (len > 0 && this[len - 1] != '/') {
                len--
            }
            dropLevels--
            // End is now messy, let the above deal with it
        }
    }

    if (dropLevels > 0 && !absolute) {
        // When path is absolute, we don't have to worry about extra drop levels, because "/.." == "/",
        // but for relative paths, we have to deal with this
        assert(len <= 0)
        this.setLength(0)
        this.append("..")
        for (i in 1 until dropLevels) {
            this.append("/..")
        }
    } else if (absolute && len <= 1) {
        this.setLength(1)
    } else {
        this.setLength(len)
    }
}

/**
 * Appends given path to the path in receiver, resolving `..` and `.` of [path] in the process.
 * Double `/` (`foo//bar`) are collapsed to single `/` (so that `"foo" / "/bar"` won't result in `"bar"`, but in `"foo/bar"`).
 */
internal fun StringBuilder.pathAppend(path:CharSequence, start:Int, end:Int) {
    var i = start
    while (i < end) {
        // Eliminate leading '/'*
        while (path[i] == '/') {
            i++
            if (i >= end) {
                break
            }
        }

        val elementStart = i

        // Search up to next '/' or eof
        while (i < end && path[i] != '/') {
            i++
        }
        val elementEnd = i

        val length = elementEnd - elementStart
        if (length == 0 || (length == 1 && path[elementStart] == '.')) {
            // Ignore empty element and self reference
        } else if (length == 2 && path[elementStart] == '.' && path[elementStart+1] == '.') {
            // Back reference
            this.pathNormalizeEnd(1) // pathNormalizeEnd has logic to deal with this correctly
        } else {
            // Normal path append
            this.pathNormalizeEnd(0)
            val len = this.length
            if (len > 0 && this[len - 1] != '/') {
                // Situations
                // 1. empty relative path - no /
                // 2. empty absolute path - no /
                // 3. non empty path - append /
                this.append('/')
            }
            this.append(path, elementStart, elementEnd)
        }
    }
}

/**
 * Appends given path to the receiver path, using [pathAppend].
 * Makes best effort to modify the receiver CharSequence, i.e.first it checks if it is a StringBuilder that can be used.
 *
 * @return this with [path] appended like path, possibly in modified receiver this
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
    sb.pathAppend(path, 0, path.length)

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
    try {
        if (host != null
                && !host.isBlank()
                && !host.equals("localhost", ignoreCase = true)
                && !host.equals("127.0.0.1", ignoreCase = true)) {
            return null
        }

        val url: URL = if (protocol == "jar") {
            // This is how JDK does it: java.net.JarURLConnection.parseSpecs
            URL(file.substring(0, file.lastIndexOf("!/")))
        } else {
            // Strip host, authority, etc.
            URL(protocol, null, file)
        }

        return if (url.protocol == "file") {
            // FileSystems.getDefault() is guaranteed to be "file" scheme FileSystem
            FileSystems.getDefault().provider().getPath(url.toURI())
        } else {
            null
        }
    } catch (e:Throwable) {
        LOG.warn("URL to Path conversion failed for {}", this)
        throw e
    }
}

/** Return true if this URL has `file` scheme. */
fun URL.isLocal():Boolean {
    return "file".equals(protocol, ignoreCase = true)
}

/** Append given path to the file. It is safe to use '/' slashes for directories
 * (this is preferred to chaining [div] calls). Do not use '\' backslash. */
inline operator fun Path.div(path: CharSequence): Path = this.resolve(path.toString())
/** Resolve this path to its sibling with given [appendix] appended. [appendix] should not contain directory separator. */
infix fun Path.appendSuffix(appendix:CharSequence):Path = this.resolveSibling(this.name + appendix)

/** @see [Files.isRegularFile] */
inline fun Path.isRegularFile(): Boolean = Files.isRegularFile(this)

/** @see [Files.isDirectory] */
inline fun Path.isDirectory(): Boolean = Files.isDirectory(this)

/** @see [Files.exists] */
inline fun Path.isHidden(): Boolean = Files.isHidden(this)

/** @see [Files.exists] */
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

internal val NO_LINK_OPTIONS = emptyArray<LinkOption>()
private val NO_FOLLOW_LINKS_OPTIONS = arrayOf(LinkOption.NOFOLLOW_LINKS)

/** Return last modified time of receiver file or -1 if it doesn't exist. Follows links. */
fun Path.lastModifiedMillis():Long {
    try {
        return Files.getLastModifiedTime(this, *NO_LINK_OPTIONS).toMillis()
    } catch (e:IOException) {
        return -1
    }
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
 * @return [BasicFileAttributes.fileKey] for this path, or null if not possible
 */
fun Path.fileKey(): Any? = try {
    Files.readAttributes(this, BasicFileAttributes::class.java).fileKey()
} catch (ignored: Exception) {
    null
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

/**
 * Copy this file or folder to [to].
 *
 * Does nothing when the file does not exist, copies as file when this is file, copies recursively all contents
 * when this is directory.
 */
fun Path.copyRecursively(to:Path, vararg options:CopyOption) {
    if (!Files.exists(this)) {
        return
    }
    if (!Files.isDirectory(this)) {
        // File
        Files.copy(this, to, *options)
    } else {
        // Directory
        val root = this
        Files.createDirectories(to.parent)
        val copyAttributes = options.contains<CopyOption>(StandardCopyOption.COPY_ATTRIBUTES)

        Files.walkFileTree(root, object : FileVisitor<Path>{

            // Based on https://stackoverflow.com/a/18691793/2694196

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(dir)
                val targetDir = to.resolve(relative)
                // Tolerate "overriding" directories
                // (used at least when copying to directory that already exists, but is empty)
                if (!Files.isDirectory(targetDir, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(targetDir)
                }

                if (copyAttributes) {
                    Files.getFileAttributeView(dir, AclFileAttributeView::class.java)?.let { acl ->
                        Files.getFileAttributeView(targetDir,AclFileAttributeView::class.java).acl = acl.acl
                    }

                    Files.getFileAttributeView(dir, DosFileAttributeView::class.java)?.readAttributes()?.let { dosAttrs ->
                        val targetDosAttrs = Files.getFileAttributeView(targetDir,DosFileAttributeView::class.java)
                        targetDosAttrs.setArchive(dosAttrs.isArchive)
                        targetDosAttrs.setHidden(dosAttrs.isHidden)
                        targetDosAttrs.setReadOnly(dosAttrs.isReadOnly)
                        targetDosAttrs.setSystem(dosAttrs.isSystem)
                    }

                    Files.getFileAttributeView(dir, FileOwnerAttributeView::class.java)?.let { ownerAttrs ->
                        val targetOwner = Files.getFileAttributeView(targetDir,FileOwnerAttributeView::class.java)
                        targetOwner.owner = ownerAttrs.owner
                    }

                    Files.getFileAttributeView(dir, PosixFileAttributeView::class.java)?.readAttributes()?.let { sourcePosix ->
                        val targetPosix = Files.getFileAttributeView(targetDir,PosixFileAttributeView::class.java)
                        targetPosix.setPermissions(sourcePosix.permissions())
                        targetPosix.setGroup(sourcePosix.group())
                    }

                    Files.getFileAttributeView(dir,UserDefinedFileAttributeView::class.java)?.let { userAttrs ->
                        val targetUser = Files.getFileAttributeView(targetDir, UserDefinedFileAttributeView::class.java)
                        for (key in userAttrs.list()) {
                            val buffer = ByteBuffer.allocate(userAttrs.size(key))
                            userAttrs.read(key, buffer)
                            buffer.flip()
                            targetUser.write(key, buffer)
                        }
                    }

                    // Must be done last, otherwise last-modified time may be wrong
                    val targetBasic = Files.getFileAttributeView(targetDir,BasicFileAttributeView::class.java)
                    targetBasic.setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime())
                }

                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, to.resolve(root.relativize(file)), *options)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                throw exc
            }
        })
    }
}

/**
 * If this file does not exist, create it as a directory.
 *
 * If this file exists and is a directory, deletes all children.
 *
 * If this file exists and is not a directory, throws [IOException].
 */
fun Path.ensureEmptyDirectory() {
    if (!Files.exists(this)) {
        Files.createDirectories(this)
    } else if (Files.isDirectory(this)) {
        Files.newDirectoryStream(this).use {
            for (path in it) {
                path.deleteRecursively()
            }
        }
    } else {
        throw IOException("$this can't be made into directory")
    }
}

/** [Path]s are comparable, but only if they belong to the same provider.
 * This comparator declares total ordering, even for different providers.
 * This ordering is not stable between program runs. */
val PATH_COMPARATOR_WITH_TOTAL_ORDERING = object : Comparator<Path> {
    override fun compare(o1: Path, o2: Path): Int {
        try {
            return o1.compareTo(o2)
        } catch (e:ClassCastException) {
            return System.identityHashCode(o1.fileSystem).compareTo(System.identityHashCode(o2.fileSystem))
        }
    }
}

/** Like [PATH_COMPARATOR_WITH_TOTAL_ORDERING], but for [LocatedPath] */
val LOCATED_PATH_COMPARATOR_WITH_TOTAL_ORDERING = object : Comparator<LocatedPath> {
    override fun compare(o1: LocatedPath, o2: LocatedPath): Int {
        return PATH_COMPARATOR_WITH_TOTAL_ORDERING.compare(o1.file, o2.file)
    }
}

private val FILE_SYNCHRONIZED_OPEN_OPTIONS = setOf<OpenOption>(
        StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
private val LOCKED_PATHS = WeakHashMap<Path, Semaphore>()

/**
 * Works like [synchronized], but the synchronization is done on a [directory] and is coordinated with other processes
 * as well.
 *
 * @param onWait optional callback that will be called once it is determined that waiting for the
 */
fun <Result> directorySynchronized(directory: Path, onWait:(()->Unit)? = null, action:() -> Result):Result {
    // Implementation of this is not trivial as all OSes have own quirks and differences

    if (!Files.exists(directory)) {
        // Otherwise toRealPath will crash
        Files.createDirectories(directory)
    }

    val lockedPath = directory.toRealPath()

    if (!Files.isDirectory(lockedPath)) {
        throw IllegalArgumentException("$lockedPath is not a directory")
    }

    // Semaphore for path locking for this process
    val semaphore =
            synchronized(LOCKED_PATHS) {
                LOCKED_PATHS.getOrPut(lockedPath) { Semaphore(1) }
            }

    val lockPath = lockedPath.resolve(".wemi-lock")

    var onWaitCalled = false

    // Coordinate with other threads
    if (!semaphore.tryAcquire()) {
        if (!onWaitCalled) {
            onWait?.invoke()
            onWaitCalled = true
        }
        semaphore.acquire()
    }

    try {
        // Coordinate with other processes

        // Create FileChannel and lock it
        var channel:FileChannel
        while (true) {

            // Construct FileChannel
            var creationAttempt = 0
            while (true) {// Thanks Lucy for helping with this
                try {
                    // Windows sometimes crashes here, but it usually fixes itself in <10 attempts
                    channel = FileChannel.open(lockPath, FILE_SYNCHRONIZED_OPEN_OPTIONS)
                    break
                } catch (e:IOException) {
                    when {
                        creationAttempt < 10 -> Thread.yield()
                        creationAttempt < 1000 -> Thread.sleep(1)
                        else -> throw e
                    }
                    creationAttempt++
                }
            }

            // Remember the fileKey before the lock is acquired
            val preLockKey = lockPath.fileKey()
            // Lock, this may take some time
            if (channel.tryLock() == null) {
                if (!onWaitCalled) {
                    onWait?.invoke()
                    onWaitCalled = true
                }
                channel.lock()
            }

            // Check, that channel points to the actual file in the filesystem, visible to other processes
            // It may happen, that it points to removed inode
            if (Files.exists(lockPath) && preLockKey == lockPath.fileKey()) {
                if (preLockKey != null) {
                    // If file keys are supported on this filesystem, we can be pretty sure that the file is correct.
                    break
                    // On Windows (for example) we have to continue
                }

                // Again check that we already have this file locked by resizing it to random size and observing if
                // the file in the filesystem behaves accordingly
                val expectedSize = 1L + Random().nextInt(1_000)
                channel.position(expectedSize-1L)
                channel.write(ByteBuffer.allocate(1))
                channel.force(false) // Flush changes
                val filesystemSize = Files.size(lockPath)
                channel.truncate(0) // Reset the size

                if (filesystemSize == expectedSize) {
                    // File passed the check, we have the file locked!
                    // Break the loop and use this channel
                    break
                }
            }

            // Close the channel and try again
            try {
                channel.close()
            } catch (ignored:IOException) {}
        }

        // Do the action
        try {
            return action()
        } finally {
            // Cleanup the lock
            // OS may allow us to delete the file while it is still open, which would be great
            var deleted = false
            try {
                Files.delete(lockPath)
                deleted = true
            } catch (ignored:IOException) {}

            // Close the channel, releasing the lock
            try {
                channel.close()
            } catch (ignored:IOException) {}

            if (!deleted) {
                // We now need to delete the lock file. Different process may have already locked on it,
                // so it may fail, which we don't care about, if it is still visible to others
                try {
                    Files.delete(lockPath)
                } catch (ignored:IOException) { }
            }
        }
    } finally {
        // Allow other threads to work with this directory
        semaphore.release()

        //TODO LOCKED_PATHS may leak memory here
    }
}