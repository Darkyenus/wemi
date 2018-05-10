package wemi.util

import com.esotericsoftware.jsonbeans.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import wemi.boot.MachineWritable
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private val LOG: Logger = LoggerFactory.getLogger("LocatedFile")

@Suppress("NOTHING_TO_INLINE")
/**
 * File that may or may not have an explicit location in the file tree.
 *
 * Used when handling classpath entries, where not only the file name and content, but also file's location in
 * the (re)source root is important.
 *
 * @property file represented
 * @property path to the file, *without* leading '/', including the file name
 * @property root to which [path] is relative to
 * @property simple is true if the (File) constructor has been used and the file is not really "located"
 */
class LocatedFile private constructor(val file: Path, val root: Path, val path: String, val simple: Boolean)
    : MachineWritable {

    //TODO Remove path, it should be determined as in third constructor, always
    //TODO Consider removing simple?
    //TODO Add assert that file is in root

    constructor(file: Path) : this(file, file.parent, file.name, true)
    constructor(file: Path, location: String, root: Path) : this(file, root, location, false)
    constructor(file: Path, root: Path) : this(file, root, root.relativize(file).toString(), false)

    init {
        assert(!file.isDirectory()) { "LocatedFile.file must not be a directory: " + this }
        assert(!root.isDirectory()) { "LocatedFile.root must be a directory: " + this }
    }

    inline operator fun component1(): Path = file
    inline operator fun component2(): String = path
    inline operator fun component3(): Path = root

    val classpathEntry: Path
        get() = if (simple) file else root

    override fun toString(): String {
        return root.absolutePath + "//" + path
    }

    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("file", file, Path::class.java)
        json.writeValue("root", root, Path::class.java)
        json.writeValue("path", path, String::class.java)
        json.writeValue("simple", simple, Boolean::class.java)
        json.writeObjectEnd()
    }
}

/**
 * Walk the filesystem, starting at [from] and create a [LocatedFile] for each encountered file,
 * that fulfills the [predicate]. Then put the created file to [to].
 *
 * Created files will have their root at [from].
 */
fun constructLocatedFiles(from: Path, to: MutableCollection<LocatedFile>, predicate: (Path) -> Boolean = { true }) {
    // Walking is hard, don't do it if we don't have to
    if (!from.exists()) {
        return
    }

    if (!from.isDirectory()) {
        if (!from.isHidden() && predicate(from)) {
            to.add(LocatedFile(from))
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
                val originalLength = pathStack.length
                pathStack.append(file.name)
                to.add(LocatedFile(file, pathStack.toString(), from))
                pathStack.setLength(originalLength)
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
