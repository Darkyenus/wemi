package wemi.util

import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private val LOG: Logger = LoggerFactory.getLogger("LocatedPath")

/**
 * Path that may have an explicit root location in the file tree.
 *
 * Used when handling classpath entries, where not only the file name and content, but also file's location in
 * the (re)source root is important. For example, *.class files are added to classpath by their root, not by their
 * full path.
 */
class LocatedPath private constructor(
        /** Explicit root, if specified, prefix of [file] */
        val root: Path?,
        /** Represented file */
        val file: Path,
        @Suppress("UNUSED_PARAMETER") dummy: Unit?)
    : JsonWritable {

    /**
     * Create without explicit root. [file] itself is an classpath entry, or this distinction is not relevant.
     * [root] will be reported as the [file]'s direct parent.
     * [classpathEntry] is considered to be [file].
     * Used, for example, for *.jar files.
     */
    constructor(file: Path) : this(null, file, null)

    /**
     * Create with explicit root.
     * [root] MUST be a prefix of [file].
     * [classpathEntry] is considered to be [root].
     * Used, for example, for *.class files.
     */
    constructor(root: Path, file: Path) : this(root, file, null) {
        assert(file.startsWith(root)) { "root must be prefix of the file" }
    }

    /**
     * Path to the file from root, *without* leading '/', including the file name
     */
    val path: String
        get() {
            if (root == null) {
                return file.name
            } else {
                return root.relativize(file).toString()
            }
        }

    val classpathEntry: Path
        get() = root ?: file

    override fun toString(): String {
        if (root == null) {
            return file.absolutePath
        } else {
            return "${root.absolutePath}//$path"
        }
    }

    override fun JsonWriter.write() {
        writeObject {
            field("root", root)
            field("file", file)
        }
    }
}

/**
 * Walk the filesystem, starting at [from] and create a [LocatedPath] for each encountered file,
 * that fulfills the [predicate]. Then put the created file to [to].
 *
 * Created files will have their root at [from].
 */
fun constructLocatedFiles(from: Path, to: MutableCollection<LocatedPath>, predicate: (Path) -> Boolean = { true }) {
    // Walking is hard, don't do it if we don't have to
    if (!from.exists()) {
        return
    }

    if (!from.isDirectory()) {
        if (!from.isHidden() && predicate(from)) {
            to.add(LocatedPath(from))
        }
        return
    }

    val pathStack = StringBuilder(64)

    Files.walkFileTree(from, object : FileVisitor<Path> {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
            if (dir !== from) {
                pathStack.append(dir.name).append('/')
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!attrs.isDirectory && !file.isHidden() && predicate(file)) {
                to.add(LocatedPath(from, file))
            }

            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (dir !== from) {
                pathStack.setLength(pathStack.length - dir.name.length - 1)
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
            LOG.warn("Can't visit {} to constructLocatedFiles", file, exc)
            return FileVisitResult.CONTINUE
        }
    })
}
