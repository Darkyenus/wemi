package wemi.util

import java.io.File

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
class LocatedFile private constructor(val file: File, val root:File, val path: String, val simple:Boolean) {

    constructor(file:File) : this(file, file.parentFile, file.name, true)
    constructor(file:File, location: String, root:File) : this(file, root, location, false)

    init {
        assert(!file.isDirectory) {"LocatedFile.file must not be a directory: " + this}
        assert(!root.isDirectory) {"LocatedFile.root must be a directory: " + this}
    }

    operator inline fun component1():  File = file
    operator inline fun component2():  String? = path
    operator inline fun component3():  File? = root

    val classpathEntry:File
        get() = if (simple) file else root

    override fun toString(): String {
        return root.absolutePath+"//"+path
    }
}

fun constructLocatedFiles(from: File, to: MutableCollection<LocatedFile>) {
    constructLocatedFiles(from, to) { _ -> true }
}

inline fun constructLocatedFiles(from: File, to: MutableCollection<LocatedFile>, filter: (File) -> Boolean) {
    // Walking is hard, don't do it if we don't have to
    if (from.isFile) {
        if (!from.isHidden && filter(from)) {
            to.add(LocatedFile(from))
        }
        return
    }

    val pathStack = StringBuilder(64)

    from.walkTopDown()
            .onEnter { directory ->
                if (directory !== from) {
                    pathStack.append(directory.name).append('/')
                }
                true
            }
            .onLeave { directory ->
                if (directory !== from) {
                    pathStack.setLength(pathStack.length - directory.name.length + 1)
                }
            }
            .forEach { file ->
                if (!file.isDirectory && !file.isHidden && filter(file)) {
                    val originalLength = pathStack.length
                    pathStack.append(file.name)
                    to.add(LocatedFile(file, pathStack.toString(), from))
                    pathStack.setLength(originalLength)
                }
            }
}
