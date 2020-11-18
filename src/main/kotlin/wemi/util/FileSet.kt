package wemi.util

import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.PrettyPrinter
import wemi.boot.WemiRootFolder
import wemi.util.FileSet.Pattern
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

private val LOG = LoggerFactory.getLogger("FileSet")

typealias SanitizedPattern = String

/**
 * Each [FileSet] describes a set of files under [root] directory which match given [patterns]
 * and may optionally form a linked list with other [FileSet]s through [next]. Resulting collection of files is then
 * a simple union of files matched by individual [FileSet]s.
 *
 * [root] is often used standalone, without regards to [patterns] and other parameters, when external tool only accepts
 * a directory. For example, compilers may use [root] as a root of the package hierarchy, or IDE plugins may use it as source root.
 * For this reason, it is advisable to set it only to the path of "natural root" for the given resource.
 *
 * For simplicity, [FileSet] may also represent a single file, when [root] is not a directory, but a file.
 * This file is then included by default and [defaultExcludes] are ignored. The file may still get excluded and re-included
 * through [patterns], which are matched directly against the file name.
 *
 * If first [Pattern] in [patterns] is not an [include] pattern, include all pattern ("∗∗") is implicitly assumed.
 * If [defaultExcludes] is true, exclude hidden files pattern (`∗∗/.∗∗`) is used as an implicit *last* pattern.
 * [caseSensitive] determines whether the patterns should be matched exactly or ignoring the case.
 * Both [defaultExcludes] and [caseSensitive] is `true` by default.
 *
 * [patterns] are applied in the order in which they are specified, i.e. file may get included by default,
 * then excluded by generic filter and then included again by a more specific filter.
 * Sometimes it may be more readable to combine multiple [FileSet]s through [plus] instead of complex pattern chain.
 *
 * Note that FileSet is only designed to match files, not directories.
 *
 * @param root if doesn't exist, [FileSet] is considered to be empty (not an error)
 * @see include to create include patterns
 * @see exclude to create include patterns
 * @see Pattern for detailed description of the used pattern syntax
 */
class FileSet(
        val root: Path,
        vararg val patterns:Pattern,
        val defaultExcludes:Boolean = true,
        val caseSensitive:Boolean = true,
        val next: FileSet? = null) : JsonWritable {

    constructor(path:LocatedPath):this(path.root ?: path.file, *if (path.root == null) emptyArray<Pattern>() else arrayOf<Pattern>(include(path.path)))

    override fun JsonWriter.write() {
        writeArray {
            var fileSet = this@FileSet
            while (true) {
                writeObject {
                    field("root", fileSet.root)
                    name("patterns").writeArray {
                        for (pattern in fileSet.patterns) {
                            pattern.run { write() }
                        }
                    }
                    field("defaultExcludes", defaultExcludes)
                    field("caseSensitive", caseSensitive)
                }
                fileSet = fileSet.next ?: break
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')

        var set = this
        while (true) {
            sb.append(root)
            for (pattern in patterns) {
                sb.append(' ').append(pattern)
            }

            if (!defaultExcludes) {
                sb.append(" (without default excludes)")
            }
            if (!caseSensitive) {
                sb.append(" (case insensitive)")
            }

            set = set.next ?: break
            sb.append(", ")
        }

        sb.append(']')
        return sb.toString()
    }

    /**
     * [patterns] syntax closely follows [ant pattern syntax](https://ant.apache.org/manual/dirtasks.html#patterns)
     * which is in turn based on shell glob syntax.
     *
     * All symbols match themselves (and possibly their opposite-case counterpart if [FileSet.caseSensitive] is `false`),
     * except for:
     * - `*` which matches zero or more characters, except `/`
     * - `?` which matches one character, except `/`
     * - `**` which matches zero or more characters, including `/`
     *
     * Patterns are matched to the relative path from [FileSet.root] (or to the file name if it is a file, see [FileSet] docs).
     * Only files under [FileSet.root] are considered, so while `../file` may exist, it will not be matched.
     * Parent directory path (`..`) is not supported and neither is self path (`.`).
     * Matching is done directory per directory and extra directory separators are ignored, so that "∗∗/file" matches
     * "foo/file", "foo/bar/file" and also just "file" (= "[root]/file").
     *
     * For compatibility with Ant, when pattern ends with `/`, `**` is appended so that it matches all files of the folder.
     * Note that unlike gitignore's syntax, trailing `/` does not force the matched file to be a directory, so
     * `thing/∗∗` will also match a regular file "thing". ([FileSet] does not allow to match directories.)
     */
    class Pattern(val type:Type, patterns:Array<String>) : JsonWritable {

        /** Sanitized pattern strings */
        val patterns:Array<SanitizedPattern> = Array(patterns.size){ i -> sanitizePattern(patterns[i]) }

        override fun toString(): String {
            val sb = StringBuilder(32)
            sb.append(type).append('(')
            patterns.joinTo(sb, ", ")
            sb.append(')')
            return sb.toString()
        }

        override fun JsonWriter.write() {
            writeObject {
                name(type.name)
                writeArray {
                    for (pattern in patterns) {
                        value(pattern)
                    }
                }
            }
        }

        /** [FileSet] initially matches either all files of the directory of its [root], or no files,
         * if the first [Pattern] is an [Include]. To obtain the matching set of files, [Pattern]s are applied in the
         * order in which they are specified, to modify the set. Note that only files accessible through the [root]
         * (without `..`) are considered. */
        enum class Type {
            /** When file matches ALL patterns, it is added to the set */
            Include,
            /** When file from set matches ALL patterns, it is removed from the set */
            Exclude,
            /** When file in set doesn't match ANY pattern, it is removed from the set */
            Filter
        }
    }
}

val FILE_SET_PRETTY_PRINTER : PrettyPrinter<FileSet?> = printer@{ it, maxElements ->
    val sb = StringBuilder()
    var set = it ?: return@printer sb.format(Color.White).append(" (empty file set)").format().append('\n')

    var numberOffset = 0
    while (true) {
        val lineStart = sb.length
        sb.format(Color.Black, format = Format.Bold)
        if (set.root.startsWith(WemiRootFolder)) {
            sb.append(WemiRootFolder.relativize(set.root))
        } else {
            sb.append(set.root)
        }
        sb.format()
        if (!set.caseSensitive) {
            sb.append(Color.White).append(" (case insensitive)").format()
        }
        val splitter = sb.length
        sb.append('\n') // This might get removed later to make the line more compact

        if (set.patterns.isNotEmpty() || !set.defaultExcludes) {
            sb.format(Color.White).append(' ')

            for (pattern in set.patterns) {
                sb.append(' ').append(pattern.type).append('(').format()
                pattern.patterns.joinTo(sb, ", ")
                sb.format(Color.White).append(')')
            }
            if (!set.defaultExcludes) {
                sb.append(" (without default excludes)")
            }
            sb.format()
        }
        sb.append('\n')

        if (sb.length - lineStart <= 120) {
            // Remove line separator between root and patterns
            sb.setCharAt(splitter, ' ')
        }

        val matchingFiles = ArrayList<Path>()
        ownMatchingFiles(set) { matchingFiles.add(it) }
        sb.appendPrettyCollection(matchingFiles, maxElements, numberOffset)
        numberOffset += matchingFiles.size

        set = set.next ?: break
    }

    sb
}

/** Strip problematic sequences which are NO-OPs, but matcher could choke on them. */
internal fun sanitizePattern(pattern:String):SanitizedPattern {
    val modified = pattern
            .removePrefix("./") // leading `./`
            .removeSuffix("/.") // trailing `/.`
            .replace(DUPLICATE_FILE_SEPARATORS, "/") // duplicate `/` and extra `/./`
            .replace(EXTRA_BLOBS, "**") // `***` (and longer) is equivalent to `**`

    if (modified.endsWith('/')) { // `bar/` -> `bar/**`
        return "$modified**"
    }
    return modified
}

/** Matches `//`, `/./`, `///`, `/.//`, etc. */
private val DUPLICATE_FILE_SEPARATORS = Regex("(?:/\\.?)+/")
/** Matches `***`, `****`, etc. */
private val EXTRA_BLOBS = Regex("\\*{3,}")

/** Shortcut for [FileSet.Pattern] with [Pattern.Type.Include]. */
@Suppress("UNCHECKED_CAST")
fun include(vararg pattern:String): Pattern = Pattern(Pattern.Type.Include, pattern as Array<String>)
/** Shortcut for [FileSet.Pattern] with [Pattern.Type.Exclude]. */
@Suppress("UNCHECKED_CAST")
fun exclude(vararg pattern:String): Pattern = Pattern(Pattern.Type.Exclude, pattern as Array<String>)
/** Shortcut for [FileSet.Pattern] with [Pattern.Type.Filter]. */
@Suppress("UNCHECKED_CAST")
fun filter(vararg pattern:String): Pattern = Pattern(Pattern.Type.Filter, pattern as Array<String>)

/** Combine two [FileSet]s. File will be in the resulting [FileSet], if it were in the first or the second one.
 * Note that if a file is matched by both, it **may** appear in [matchingFiles]. */
operator fun FileSet?.plus(fileSet:FileSet?):FileSet? {
    if (this == null || fileSet == null) {
        return this ?: fileSet
    }
    var me:FileSet = this
    var other:FileSet = fileSet
    while (true) {
        me = FileSet(other.root, *other.patterns,
                defaultExcludes = other.defaultExcludes, caseSensitive = other.caseSensitive, next = me)
        other = other.next ?: break
    }
    return me
}

/** Return receiver with given [patterns] appended. */
fun FileSet?.appendPatterns(vararg patterns:Pattern):FileSet? {
    if (this == null || patterns.isEmpty()) return this
    return FileSet(root, *this.patterns, *patterns, defaultExcludes = defaultExcludes, caseSensitive = caseSensitive, next = this.next.appendPatterns(*patterns))
}

/** See [filterByExtension]. */
val ALL_EXTENSIONS = emptyArray<String>()

/** Return receiver [FileSet] filtered to contain only files with any of given extensions.
 * Returns the same [FileSet] if [extension] is empty. */
fun FileSet?.filterByExtension(vararg extension:String):FileSet? {
    if (this == null || extension.isEmpty()) return this
    return this.appendPatterns(filter(*Array(extension.size) { i -> "**.${extension[i]}"}))
}

/** Find all files which are matched by the receiver. */
fun FileSet?.matchingFiles():List<Path> {
    val set = this ?: return emptyList()
    val result = ArrayList<Path>()
    set.matchingFiles(result)
    return result
}

/** Find all files which are matched by the receiver. */
fun FileSet?.matchingLocatedFiles():List<LocatedPath> {
    val set = this ?: return emptyList()
    val result = ArrayList<LocatedPath>()
    set.matchingLocatedFiles(result)
    return result
}

/** Find all files which are matched by the receiver and place them into the [into] collection. */
fun FileSet.matchingFiles(into:MutableCollection<Path>) {
    var fileSet = this
    while (true) {
        ownMatchingFiles(fileSet) {
            into.add(it)
        }
        fileSet = fileSet.next ?: break
    }
}

/** Find all files which are matched by the receiver and place them into the [into] collection.
 * [LocatedPath.root] is set to corresponding [FileSet.root] (unless single file is matched). */
fun FileSet.matchingLocatedFiles(into:MutableCollection<LocatedPath>) {
    var fileSet = this
    while (true) {
        ownMatchingFiles(fileSet) {
            if (fileSet.root === it) {
                into.add(LocatedPath(it))
            } else {
                into.add(LocatedPath(fileSet.root, it))
            }
        }
        fileSet = fileSet.next ?: break
    }
}

/** Does the matching itself. */
internal fun patternMatches(pattern:SanitizedPattern, path:CharSequence, caseSensitive:Boolean):Boolean {
    // TODO(jp): This recursive approach is pretty slow in some cases, rewrite to (N|D)FA
    //  https://swtch.com/~rsc/regexp/
    // https://swtch.com/~rsc/regexp/regexp2.html Thompson approach is nice
    val ignoreCase = !caseSensitive

    fun matchSingle(patternStart:Int, pathStart:Int):Boolean {
        if (patternStart >= pattern.length) {
            // Matching EOL
            return pathStart >= path.length
        }
        val token = pattern[patternStart]
        if (token == '?') {
            if (pathStart < path.length) {
                val inPath = path[pathStart]
                if (inPath == '/') {
                    // Can't match `?` to `/`
                    return false
                } else {
                    // Success, advance
                    return matchSingle(patternStart + 1, pathStart + 1)
                }
            } else {
                // Can't match wildcard, end of path
                return false
            }
        } else if (token == '*') {
            if (patternStart + 1 < pattern.length && pattern[patternStart + 1] == '*') {
                // `**` pattern
                val nextPatternStart = patternStart + 2
                if (nextPatternStart == pattern.length) {
                    // Trailing `**`, now we will always succeed (optimization)
                    return true
                }
                val nextPatternToken = pattern[nextPatternStart]
                assert(nextPatternToken != '*') // Removed at sanitization
                when (nextPatternToken) {
                    '?' -> {
                        // Tough case, hard to optimize, be naive
                        for (nextPathStart in pathStart until path.length) {
                            if (matchSingle(nextPatternStart, nextPathStart)) {
                                return true
                            }
                        }
                        return false
                    }
                    '/' -> {
                        var nextPathStart =
                        // `/**/` can collapse to `/`
                                if (pathStart <= 0 || path[pathStart - 1] == '/') pathStart
                                else path.indexOf('/', pathStart)
                        while (true) {
                            if (nextPathStart == -1) {
                                // `/` can double for EOL
                                return matchSingle(nextPatternStart, path.length)
                            }
                            if (matchSingle(nextPatternStart, nextPathStart)) {
                                return true
                            }
                            nextPathStart = path.indexOf('/', nextPathStart + 1)
                        }
                    }
                    else -> {
                        var nextPathStart = path.indexOf(nextPatternToken, pathStart, ignoreCase)
                        while (true) {
                            if (nextPathStart == -1) {
                                return false
                            }
                            if (matchSingle(nextPatternStart, nextPathStart)) {
                                return true
                            }
                            nextPathStart = path.indexOf(nextPatternToken, nextPathStart + 1, ignoreCase)
                        }
                    }
                }
            } else {
                // `*` pattern
                val nextSlashIndex = path.indexOf('/', pathStart).let {
                    if (it == -1) path.length else it
                }

                val nextPatternStart = patternStart + 1
                if (nextPatternStart >= pattern.length) {
                    // All we have is `*` and we need to match the whole rest of the path to it. Is it possible?
                    // Yes, if all we have now is the last file name (possibly with trailing `/`, which should be sanitized away anyway)
                    return nextSlashIndex >= path.lastIndex
                }

                val nextPatternToken = pattern[nextPatternStart]
                assert(nextPatternToken != '*') // Just checked

                when (nextPatternToken) {
                    '?' -> {
                        // Tough case, hard to optimize, be naive
                        for (nextPathStart in pathStart until nextSlashIndex) {
                            if (matchSingle(nextPatternStart, nextPathStart)) {
                                return true
                            }
                        }
                        return false
                    }
                    '/' -> {
                        // We just take this file without asking
                        return matchSingle(nextPatternStart, nextSlashIndex)
                    }
                    else -> {
                        var nextPathStart = path.indexOf(nextPatternToken, pathStart, ignoreCase)
                        while (true) {
                            if (nextPathStart == -1 || nextPathStart >= nextSlashIndex) {
                                // Good start not found in this word
                                return false
                            }
                            if (matchSingle(nextPatternStart, nextPathStart)) {
                                return true
                            }
                            nextPathStart = path.indexOf(nextPatternToken, nextPathStart + 1, ignoreCase)
                        }
                    }
                }
            }
        } else if (token == '/') {
            // Path boundary
            if (pathStart >= path.length) {
                // Just skip and let whatever pattern follows solve it
                return matchSingle(patternStart + 1, path.length)
            }
            if (path[pathStart] == '/') {
                // Got what we need, continue
                return matchSingle(patternStart + 1, pathStart + 1)
            }
            if (pathStart <= 0 || path[pathStart - 1] == '/') {
                // Leading `/`, also valid path boundary
                return matchSingle(patternStart + 1, pathStart)
            }
            // Not found valid path boundary
            return false
        } else {
            // Regular letter(s)
            var currentPathStart = pathStart
            var currentPatternStart = patternStart
            var currentPatternToken = token

            while (true) {
                if (currentPathStart >= path.length) {
                    // Can't match that
                    return false
                }
                val nextPathItem = path[currentPathStart]
                if (!currentPatternToken.equals(nextPathItem, ignoreCase)) {
                    // Failed, boo
                    return false
                }
                // Matched, try another one
                currentPathStart++
                currentPatternStart++
                if (currentPatternStart >= pattern.length) {
                    // Matched whole pattern, we are done if that covered whole path
                    return currentPathStart >= path.length
                }
                currentPatternToken = pattern[currentPatternStart]
                when (currentPatternToken) {
                    '?', '*', '/' ->
                        return matchSingle(currentPatternStart, currentPathStart)
                }
                // Otherwise just continue
            }
        }
    }

    return matchSingle(0, 0)
}

/** Pattern used if [FileSet.defaultExcludes] is on.
 * Matches all hidden files (those starting with .) */
private const val DEFAULT_EXCLUDE_PATTERN = "**/.**"

/** Tests if given [path] should be included based on [patterns] (and possibly [DEFAULT_EXCLUDE_PATTERN] if [defaultExcludes]).
 * @param caseSensitive passed through to [patternMatches] */
internal fun matches(patterns: Array<out Pattern>, defaultExcludes:Boolean, caseSensitive:Boolean, path:CharSequence):Boolean {
    if (defaultExcludes && patternMatches(DEFAULT_EXCLUDE_PATTERN, path, false)) {
        return false
    }

    for (i in patterns.indices.reversed()) {
        val pattern = patterns[i]
        when (pattern.type) {
            Pattern.Type.Include ->
                if (pattern.patterns.all { patternMatches(it, path, caseSensitive) }) return true
            Pattern.Type.Exclude ->
                if (pattern.patterns.all { patternMatches(it, path, caseSensitive) }) return false
            Pattern.Type.Filter ->
                if (pattern.patterns.all { !patternMatches(it, path, caseSensitive) }) return false
        }
    }

    if (patterns.isEmpty() || patterns[0].type != Pattern.Type.Include) {
        // If there are no patterns at all, or if the first pattern is exclude, path is included by default
        return true
    }
    // First pattern is include and it didn't match, so no match at all
    return false
}

/** Like [matchingFiles], but ignoring [FileSet.next]. */
private fun ownMatchingFiles(fileSet:FileSet, collect:(Path) -> Unit) {
    val rootAttributes = try {
        Files.readAttributes<BasicFileAttributes>(fileSet.root, BasicFileAttributes::class.java, *NO_LINK_OPTIONS)
    } catch (e: IOException) {
        // Root doesn't exist
        return
    }
    if (!rootAttributes.isDirectory) {
        // Special logic for non-directories
        if (matches(fileSet.patterns, false, fileSet.caseSensitive, fileSet.root.name)) {
            collect(fileSet.root)
        }
        return
    }

    val pathBuilder = StringBuilder(64)
    Files.walkFileTree(fileSet.root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Int.MAX_VALUE, object : FileVisitor<Path> {
        var waitingForRoot = true

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (waitingForRoot) {
                // We always visit the root first, but we don't care about it
                waitingForRoot = false
                return FileVisitResult.CONTINUE
            }

            pathBuilder.append(dir.name).append('/')
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (pathBuilder.isEmpty()) {
                if (waitingForRoot) {
                    throw IllegalStateException("Unbalanced visitation: $dir")
                }
                waitingForRoot = true
                return FileVisitResult.CONTINUE // Technically should be last call, but just in case
            }

            assert(pathBuilder[pathBuilder.lastIndex] == '/')
            val nextDirSlash = pathBuilder.lastIndexOf('/', pathBuilder.lastIndex - 1)
            pathBuilder.setLength(nextDirSlash + 1) // Works even if no / found
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val originalLength = pathBuilder.length
            pathBuilder.append(file.name)
            if (matches(fileSet.patterns, fileSet.defaultExcludes, fileSet.caseSensitive, pathBuilder)) {
                collect(file)
            }
            pathBuilder.setLength(originalLength)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            LOG.debug("Ignoring {} for read failure", file, exc)
            return FileVisitResult.CONTINUE
        }
    })
}