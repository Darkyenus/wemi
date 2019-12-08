@file:Suppress("unused", "NOTHING_TO_INLINE")

package com.darkyen.wemi.intellij.util

import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.util.*

private val LOG = LoggerFactory.getLogger("Files")

/**
 * Creates an URL with given path appended.
 */
inline operator fun URL.div(path: CharSequence): URL {
    return URL(this, path.toString())
}

fun Path.toUrl():String {
    var fullPath = this.toAbsolutePath().normalize()
    try {
        fullPath = fullPath.toRealPath()
    } catch (e:IOException) {
        // Ignored, file probably does not exist
    }
    return VirtualFileManager.constructUrl(LocalFileSystem.PROTOCOL, FileUtilRt.toSystemIndependentName(fullPath.toString()))
}

fun Path.toClasspathUrl():String {
    if (this.isDirectory() || !this.name.pathHasExtension("jar")) {
        return this.toUrl()
    }
    var fullPath = this.toAbsolutePath().normalize()
    try {
        fullPath = fullPath.toRealPath()
    } catch (e:IOException) {
        // Ignored, file probably does not exist
    }
    return VirtualFileManager.constructUrl(JarFileSystem.PROTOCOL, FileUtilRt.toSystemIndependentName(fullPath.toString()) + JarFileSystem.JAR_SEPARATOR)
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
    try {
        if (host != null
                && !host.isBlank()
                && !host.equals("localhost", ignoreCase = true)
                && !host.equals("127.0.0.1", ignoreCase = true)) {
            return null
        }

        val url: URL

        if (protocol == "jar") {
            // This is how JDK does it: java.net.JarURLConnection.parseSpecs
            url = URL(file.substring(0, file.lastIndexOf("!/")))
        } else {
            // Strip host, authority, etc.
            url = URL(protocol, null, file)
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

/** Append given path to the file. It is safe to use '/' slashes for directories
 * (this is preferred to chaining [div] calls). Do not use '\' backslash. */
inline operator fun Path.div(path: CharSequence): Path = this.resolve(path.toString())

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

    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
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

private val LOCK_FILE_OPEN_OPTIONS = setOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, StandardOpenOption.SPARSE)
private val LOCK_FILE_STALE_CHECK_OPEN_OPTIONS = setOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE)
private val NO_FOLLOW_LINKS = arrayOf(LinkOption.NOFOLLOW_LINKS)
private val RANDOM = Random()

/*
Locking scheme:

1. Locking process exclusively creates a lock file. Existence of this file signifies, that the directory is locked.
2. After lock is no longer needed, the lock file is removed

Problem: Stale lock detection and removal (process that has created the lock crashes before it can remove the lock file)
1. After lock file is created, it is exclusively locked
2. If a process which did not create the file manages to lock the file, it is considered stale and ready to be deleted
3. Process that wishes to remove stale lock file, has to lock the file. Then it has to test if it holds correct file.
    Then it can delete it and attempt to create a new lock.
 */

/** Create a lock file path from [directory] path. If the directory does not yet exist, it will be created.
 * If the lock file exists and is not an ordinary file, error is logged and null is returned. */
private fun lockFileFromDirectory(directory:Path):Path? {
    try {
        Files.createDirectories(directory)
    } catch (fileExists:FileAlreadyExistsException) {
        LOG.warn("[lockFileFromDirectory] Can't create a lock on {}, because it is not a directory", directory)
        return null
    } catch (io:IOException) {
        LOG.warn("[lockFileFromDirectory] Can't create a lock on {}", directory, io)
        return null
    }

    val lockPath = directory.toAbsolutePath().resolve(".wemi-lock")

    val attributes:BasicFileAttributes = try {
        Files.readAttributes(lockPath, BasicFileAttributes::class.java, *NO_FOLLOW_LINKS)
    } catch (notExists:NoSuchFileException) {
        // This is fine
        return lockPath
    } catch (io:IOException) {
        // This is bad
        LOG.warn("[lockFileFromDirectory] Failed to read attributes of lock file {}", lockPath, io)
        return null
    }

    if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.isOther) {
        LOG.warn("[lockFileFromDirectory] Can't use {} as a lock file, it already exists and is not a regular file", lockPath)
        return null
    }

    return lockPath
}

@Throws(IOException::class)
private fun doesChannelMatchFile(channel:FileChannel, file:Path, proofOfLock:FileLock):Boolean {
    if (!proofOfLock.isValid) {
        LOG.warn("[doesChannelMatchFile] Proof of lock is not valid")
        return false
    }

    for (i in 0 until 3) {
        val size = 1L + RANDOM.nextInt(8192)
        channel.truncate(0L).position(size - 1L)
        val zeroBuffer = ByteBuffer.allocate(1)
        channel.write(zeroBuffer)
        val foundSize = try {
            Files.size(file)
        } catch (noFile: NoSuchFileException) {
            -1
        }

        if (foundSize != size) {
            return false
        }
    }

    return true
}

@Throws(IOException::class)
private fun createNurseryLockFile(lockFile:Path):Pair<Path, FileLock>? {
    val lockFileNursery = lockFile.resolveSibling("%s.%08x".format(lockFile.name, RANDOM.nextInt()))

    val channel = try {
        FileChannel.open(lockFileNursery, LOCK_FILE_OPEN_OPTIONS)
    } catch (alreadyExists:FileAlreadyExistsException) {
        LOG.warn("[createNurseryLockFile] Nursery lock file already exists") // Could happen during large contention
        return null
    } catch (io:IOException) {
        throw IOException("Could not create a nursery lock file $lockFileNursery", io)
    }

    var success = false
    try {
        val lock = try {
            channel.tryLock()
        } catch (overlap: OverlappingFileLockException) {
            // Lock is already held by this process
            LOG.error("[createNurseryLockFile] This process stole my nursery lock file {}", lockFile)
            null
        } catch (io: IOException) {
            throw IOException("Nursery lock file channel lock failed", io)
        } ?: run {
            LOG.error("[createNurseryLockFile] Someone stole my nursery lock file {}", lockFile)
            return null
        }
        // When returned lock is null, someone was faster, probably checking for lock staleness. It will be deleted soon.

        success = true
        return lockFileNursery to lock
    } finally {
        if (!success) {
            try {
                Files.delete(lockFileNursery)
            } catch (e:Exception) {
                LOG.warn("[createLockFile] Exception while deleting leftover lock file nursery", lockFileNursery, e)
            }

            try {
                channel.close()
            } catch (e: Exception) {
                LOG.warn("[createLockFile] Exception while closing file channel of my {}", lockFile, e)
            }
        }
    }
}

/** Called when under suspicion that the [lockFile] is stale and should be deleted.
 * Checks if the file is stale and if it is, it deletes it.
 * @return true if the lock file is no more, false if it is still locked */
private fun deleteLockFileIfStale(lockFile:Path):Boolean {
    val staleChannel = try {
        FileChannel.open(lockFile, LOCK_FILE_STALE_CHECK_OPEN_OPTIONS)
    } catch (notExists:NoSuchFileException) {
        // Looks like the file was just unlocked!
        return true
    } catch (io:IOException) {
        // This is really strange, bail!
        LOG.warn("[deleteLockFileIfStale] Failed to open lock file {} for staleness check", io)
        return false
    }

    try {
        val lock = try {
            staleChannel.tryLock()
        } catch (overlap: OverlappingFileLockException) {
            // Lock is already held by this process
            return false
        } catch (io: IOException) {
            LOG.warn("[deleteLockFileIfStale] Failed to lock the lock file {} for staleness check", lockFile, io)
            return false
        } ?: return false // When null, someone has already locked it, no need to worry about it further

        // File has been locked by a process which has not created it.
        // This means that the file is stale (or that we have managed to steal it from the creating process).
        // In either case, we have to delete it.

        try {
            if (!doesChannelMatchFile(staleChannel, lockFile, lock)) {
                LOG.debug("[deleteLockFileIfStale] Acquired lock file is not current, it has been stolen")
                return true
            }
        } catch (io:IOException) {
            LOG.warn("[deleteLockFileIfStale] Failed to verify that acquired lock file is correct", io)
            return false
        }

        try {
            Files.delete(lockFile)
        } catch (noFile: NoSuchFileException) {
            LOG.error("[deleteLockFileIfStale] Someone deleted lock file {}, when it was my job", lockFile, lock.isValid, lock.isShared)
            return true
        } catch (dirNotEmpty: DirectoryNotEmptyException) {
            LOG.warn("[deleteLockFileIfStale] Lock file {} has turned into a directory, this is most unusual", lockFile, dirNotEmpty)
            return false
        } catch (io: IOException) {
            LOG.warn("[deleteLockFileIfStale] Failed to delete stale lock file {}", lockFile, io)
            return false
        }

        LOG.warn("[deleteLockFileIfStale] Deleted stale lock file {}", lockFile)
        return true
    } finally {
        try {
            staleChannel.close()
        } catch (e:Exception) {
            LOG.warn("[deleteLockFileIfStale] Exception while closing lock file channel of {}", lockFile, e)
        }
    }
}

/**
 * Works like [synchronized], but the synchronization is done on a [directory] and is coordinated with other processes
 * as well.
 *
 * @param onWait optional callback that will be called once it is determined that waiting for the lock will take some time
 */
fun <Result> directorySynchronized(directory:Path, onWait:(()->Unit)? = null, action:() -> Result):Result {
    val lockFile = lockFileFromDirectory(directory) ?: throw IllegalArgumentException("$directory can't be locked")

    val startTime = System.currentTimeMillis()

    var nursery:Pair<Path, FileLock>? = null
    var ownsLock = false
    try {
        for (attempt in 0 until 10000) {
            if (nursery == null) {
                nursery = createNurseryLockFile(lockFile)
            }

            if (nursery != null) {
                try {
                    Files.createLink(lockFile, nursery.first)
                } catch (alreadyExists: FileAlreadyExistsException) {
                    // Already locked, bail
                    continue
                } catch (io: IOException) {
                    throw IOException("Failed to hardlink nursery lock file", io)
                }
                ownsLock = true
                break
            }

            if (attempt == 0 && onWait != null) {
                onWait()
            } else if (attempt >= 100 && deleteLockFileIfStale(lockFile)) {
                // Lock file deleted successfully, try to lock it
                continue
            } else {
                // Randomized linear backoff (About a day of waiting)
                Thread.sleep(System.nanoTime() and 7L + attempt * 2L)
            }
        }

        if (nursery == null || !ownsLock) {
            throw RuntimeException("Could not synchronize on $directory, timed out after ${"%.3f".format((System.currentTimeMillis() - startTime) / (1000.0 * 60.0 * 60.0))} hours")
        }

        try {
            return action()
        } finally {
            if (!nursery.second.isValid) {
                LOG.error("[directorySynchronized] Lock on {} is no longer valid, not deleting the lock file", lockFile)
            } else {
                try {
                    Files.delete(lockFile)
                } catch (noFile: NoSuchFileException) {
                    LOG.error("[directorySynchronized] Someone deleted my lock file {}", lockFile)
                } catch (dirNotEmpty: DirectoryNotEmptyException) {
                    LOG.error("[directorySynchronized] My lock file {} has turned into a directory, this is most unusual", lockFile, dirNotEmpty)
                } catch (io: IOException) {
                    LOG.warn("[directorySynchronized] Failed to delete my lock file {}", lockFile, io)
                }
            }
        }
    } finally {
        if (nursery != null) {
            try {
                Files.delete(nursery.first)
            } catch (e:Exception) {
                LOG.warn("[directorySynchronized] Exception while deleting leftover lock file nursery", e)
            }

            try {
                nursery.second.acquiredBy().close()
            } catch (e: Exception) {
                LOG.warn("[directorySynchronized] Exception while closing nursery lock channel", e)
            }
        }
    }
}