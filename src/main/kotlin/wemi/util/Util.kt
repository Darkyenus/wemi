package wemi.util

import com.darkyen.tproll.util.PrettyPrinter
import org.slf4j.LoggerFactory
import wemi.Key
import wemi.boot.CLI
import wemi.boot.WemiColorOutputSupported
import wemi.dependency.DEFAULT_TYPE
import wemi.dependency.DependencyId
import wemi.dependency.NoClassifier
import wemi.dependency.ResolvedDependency
import java.util.*

//region StringBuilder enhancements
fun Appendable.append(integer:Int):Appendable {
    if (this is StringBuilder) {
        this.append(integer)
    } else {
        this.append(integer.toString())
    }
    return this
}

fun Appendable.append(long:Long):Appendable {
    if (this is StringBuilder) {
        this.append(long)
    } else {
        this.append(long.toString())
    }
    return this
}

/**
 * Format given [ms] duration as a human readable duration string.
 *
 * Example output: "1 day 5 minutes 33 seconds 0 ms"
 */
fun Appendable.appendTimeDuration(ms: Long): Appendable {
    val SECOND = 1000
    val MINUTE = SECOND * 60
    val HOUR = MINUTE * 60
    val DAY = HOUR * 24

    val result = this
    var remaining = ms

    val days = remaining / DAY
    remaining %= DAY
    val hours = remaining / HOUR
    remaining %= HOUR
    val minutes = remaining / MINUTE
    remaining %= MINUTE
    val seconds = remaining / SECOND
    remaining %= SECOND

    if (days == 1L) {
        result.append("1 day ")
    } else if (days > 1L) {
        result.append(days).append(" days ")
    }

    if (hours == 1L) {
        result.append("1 hour ")
    } else if (hours > 1L) {
        result.append(hours).append(" hours ")
    }

    if (minutes == 1L) {
        result.append("1 minute ")
    } else if (minutes > 1L) {
        result.append(minutes).append(" minutes ")
    }

    if (seconds == 1L) {
        result.append("1 second ")
    } else if (seconds > 1L) {
        result.append(seconds).append(" seconds ")
    }

    result.append(remaining).append(" ms")
    return result
}

/** Like [appendTimeDuration], but only shows most significant unit. */
fun Appendable.appendShortTimeDuration(ms: Long): Appendable {
    val SECOND = 1000
    val MINUTE = SECOND * 60
    val HOUR = MINUTE * 60
    val DAY = HOUR * 24

    var remaining = ms

    val days = remaining / DAY
    if (days == 1L) {
        return append("1 day")
    } else if (days > 1L) {
        return append(days).append(" days")
    }

    remaining %= DAY
    val hours = remaining / HOUR
    if (hours == 1L) {
        return append("1 hour ")
    } else if (hours > 1L) {
        return append(hours).append(" hours ")
    }

    remaining %= HOUR
    val minutes = remaining / MINUTE
    if (minutes == 1L) {
        return append("1 minute ")
    } else if (minutes > 1L) {
        return append(minutes).append(" minutes ")
    }

    remaining %= MINUTE
    val seconds = remaining / SECOND
    if (seconds == 1L) {
        return append("1 second ")
    } else if (seconds > 1L) {
        return append(seconds).append(" seconds ")
    }

    remaining %= SECOND
    return append(remaining).append(" ms")
}

/**
 * Format given [bytes] amount as a human readable duration string.
 * Uses SI units. Only two most significant units are used, rest is truncated.
 *
 * Example output: "1 TB 5 GB"
 */
fun Appendable.appendByteSize(bytes: Long): Appendable {
    val KILO = 1000L
    val MEGA = 1000_000L
    val GIGA = 1000_000_000L
    val TERA = 1000_000_000_000L

    var remaining = bytes

    val tera = remaining / TERA
    remaining %= TERA
    val giga = remaining / GIGA
    remaining %= GIGA
    val mega = remaining / MEGA
    remaining %= MEGA
    val kilo = remaining / KILO
    remaining %= KILO

    val R = 2
    var relevant = R
    var first = true

    if ((tera > 0L || relevant < R) && relevant > 0) {
        if (first) {
            first = false
        } else {
            append(' ')
        }
        append(tera).append(" TB")
        relevant--
    }

    if ((giga > 0L || relevant < R) && relevant > 0) {
        if (first) {
            first = false
        } else {
            append(' ')
        }
        append(giga).append(" GB")
        relevant--
    }

    if ((mega > 0L || relevant < R) && relevant > 0) {
        if (first) {
            first = false
        } else {
            append(' ')
        }
        append(mega).append(" MB")
        relevant--
    }

    if ((kilo > 0L || relevant < R) && relevant > 0) {
        if (first) {
            first = false
        } else {
            append(' ')
        }
        append(kilo).append(" kB")
        relevant--
    }

    if (relevant > 0) {
        if (!first) {
            append(' ')
        }
        append(remaining).append(" B")
    }

    return this
}

/** Like [appendByteSize], but only most significant unit and no spaces. */
fun Appendable.appendShortByteSize(bytes: Long): Appendable {
    val KILO = 1000L
    val MEGA = 1000_000L
    val GIGA = 1000_000_000L
    val TERA = 1000_000_000_000L

    var remaining = bytes

    val tera = remaining / TERA
    if (tera > 0L) {
        return append(tera).append("TB")
    }

    remaining %= TERA
    val giga = remaining / GIGA
    if (giga > 0L) {
        return append(giga).append("GB")
    }

    remaining %= GIGA
    val mega = remaining / MEGA
    if (mega > 0L) {
        return append(mega).append("MB")
    }

    remaining %= MEGA
    val kilo = remaining / KILO
    if (kilo > 0L) {
        return append(kilo).append("kB")
    }

    remaining %= KILO
    return append(remaining).append('B')
}

/** Append given [character] multiple [times] */
fun Appendable.appendTimes(character:Char, times:Int):Appendable {
    if (times <= 0) {
        return this
    }
    if (this is StringBuilder) {
        ensureCapacity(times)
    }
    for (i in 0 until times) {
        append(character)
    }
    return this
}

/** Append given [text] centered in [width], padded by [padding] */
fun Appendable.appendCentered(text:String, width:Int, padding:Char):Appendable {
    val padAmount = width - text.length
    if (padAmount <= 0) {
        return append(text)
    }

    val leftPad = padAmount / 2
    val rightPadding = padAmount - leftPad
    return appendTimes(padding, leftPad).append(text).appendTimes(padding, rightPadding)
}

/** Append given [number], prefixed with [padding] to take up at least [width]. */
fun StringBuilder.appendPadded(number:Int, width:Int, padding:Char):StringBuilder {
    val originalLength = length
    append(number)
    while (length < originalLength + width) {
        insert(originalLength, padding)
    }
    return this
}
//endregion

//region Pretty printing
internal fun StringBuilder.appendPrettyValue(value:Any?, maxCollectionElements:Int = Int.MAX_VALUE):StringBuilder {
    if (value is WithDescriptiveString) {
        val valueText = value.toDescriptiveAnsiString()
        if (valueText.contains(ANSI_ESCAPE)) {
            this.append(valueText)
        } else {
            this.format(Color.Blue).append(valueText).format()
        }
    } else {
        this.format(Color.Blue)
        PrettyPrinter.append(this, value, maxCollectionElements)
        this.format()
    }
    return this
}

internal fun StringBuilder.appendPrettyCollection(collection:Collection<*>, maxCollectionElements:Int, offset:Int = 0):StringBuilder {
    val size = collection.size
    // Don't skip if it would skip only a single element
    val wouldSkip = size - maxCollectionElements
    val firstSkipped = if (wouldSkip >= 2) maxCollectionElements - 1 else Int.MAX_VALUE
    val numberWidth = (size + offset).toString().length
    for ((i, item) in collection.withIndex()) {
        if (i < firstSkipped || i >= size - 1) {
            this.format(Color.White).appendPadded(i + 1 + offset, numberWidth, ' ').append(": ").format().appendPrettyValue(item).append('\n')
        } else if (i == firstSkipped) {
            this.format(Color.White).append("    (").append(wouldSkip).append(" elements skipped)").format().append('\n')
        }
    }

    return this
}

private val APPEND_KEY_RESULT_LOG = LoggerFactory.getLogger("AppendKeyResult")

/**
 * Append the [value] formatted like the result of the [key] and newline.
 */
fun <V> StringBuilder.appendKeyResultLn(key: Key<V>, value:V, maxCollectionElements:Int = Int.MAX_VALUE) {
    val prettyPrinter = key.prettyPrinter

    if (prettyPrinter != null) {
        val printed: CharSequence? =
                try {
                    prettyPrinter(value, maxCollectionElements)
                } catch (e: Exception) {
                    APPEND_KEY_RESULT_LOG.warn("Pretty-printer for {} failed", key, e)
                    null
                }

        if (printed != null) {
            this.append(printed)
            return
        }
    }

    when (value) {
        null, Unit -> {
            this.append('\n')
        }
        is Array<*> -> {
            appendPrettyCollection(value.asList(), maxCollectionElements)
        }
        is Collection<*> -> {
            appendPrettyCollection(value, maxCollectionElements)
        }
        else -> this.appendPrettyValue(value, maxCollectionElements).append('\n')
    }
}
//endregion

//region Codepoints
/**
 * Represents index into the [CharSequence].
 */
typealias Index = Int
/**
 * Represents unicode code point.
 */
typealias CodePoint = Int

/**
 * Call [action] for each [CodePoint] in the char sequence.
 * Treats incomplete codepoints as complete.
 *
 * @param action function that takes index at which the codepoint starts and the codepoint itself
 */
inline fun CharSequence.forCodePointsIndexed(action: (index:Index, cp:CodePoint) -> Unit) {
    val length = this.length
    var i = 0

    while (i < length) {
        val baseIndex = i
        val c1 = get(i++)
        if (!Character.isHighSurrogate(c1) || i >= length) {
            action(baseIndex, c1.toInt())
        } else {
            val c2 = get(i)
            if (Character.isLowSurrogate(c2)) {
                i++
                action(baseIndex, Character.toCodePoint(c1, c2))
            } else {
                action(baseIndex, c1.toInt())
            }
        }
    }
}

/**
 * @see [forCodePointsIndexed] but ignores the index
 */
inline fun CharSequence.forCodePoints(action: (cp:CodePoint) -> Unit) {
    forCodePointsIndexed { _, cp ->
        action(cp)
    }
}
//endregion

//region File names
/**
 * @return true if this [CodePoint] is regarded as safe to appear inside a file name,
 * for no particular, general file system.
 *
 * Some rejected characters are technically valid, but confusing, for example quotes or pipe.
 */
fun CodePoint.isCodePointSafeInFileName(): Boolean = when {
    !Character.isValidCodePoint(this) -> false
    this < ' '.toInt() -> false
    this == '/'.toInt() || this == '\\'.toInt() -> false
    this == '*'.toInt() || this == '%'.toInt() || this == '?'.toInt() -> false
    this == ':'.toInt() || this == '|'.toInt() || this == '"'.toInt() -> false
    else -> true
}

/**
 * Replaces all [CodePoint]s in this [CharSequence] that are not safe to appear in a file name,
 * to [replacement].
 *
 * @see isCodePointSafeInFileName
 */
fun CharSequence.toSafeFileName(replacement: Char): CharSequence {
    val sb = StringBuilder(length)
    var anyReplacements = false

    forCodePoints { cp ->
        if (cp.isCodePointSafeInFileName()) {
            sb.appendCodePoint(cp)
        } else {
            sb.append(replacement)
            anyReplacements = true
        }
    }

    return if (anyReplacements) {
        sb
    } else {
        this
    }
}
//endregion

//region TreePrinting
/**
 * Print pretty, human readable ASCII tree, that starts at given [roots].
 *
 * Result is stored in [result] and nodes are printed through [print].
 *
 * [print] may even print multiple lines of text.
 */
fun <T> printTree(roots: Collection<TreeNode<T>>, result: StringBuilder = StringBuilder(),
                  print: T.(StringBuilder) -> Unit): CharSequence {
    if (roots.isEmpty()) {
        return ""
    }

    val prefix = StringBuilder()

    fun TreeNode<T>.println() {
        run {
            val prePrintLength = result.length
            this.value.print(result)
            // Add prefixes before new line-breaks
            var i = result.length - 1
            while (i >= prePrintLength) {
                if (result[i] == '\n') {
                    result.insert(i + 1, prefix)
                }
                i--
            }
        }
        result.append('\n')

        // Print children
        val prevPrefixLength = prefix.length
        val dependenciesSize = this.size
        this.forEachIndexed { index, dependency ->
            prefix.setLength(prevPrefixLength)
            result.append(prefix)
            if (index + 1 == dependenciesSize) {
                result.append(if (wemi.boot.WemiUnicodeOutputSupported) "╘ " else "\\=")
                prefix.append("  ")
            } else {
                result.append(if (wemi.boot.WemiUnicodeOutputSupported) "╞ " else "|=")
                prefix.append(if (wemi.boot.WemiUnicodeOutputSupported) "│ " else "| ")
            }

            dependency.println()
        }
        prefix.setLength(prevPrefixLength)
    }

    val rootsSize = roots.size

    roots.forEachIndexed { rootIndex, root ->
        if (rootIndex + 1 == rootsSize) {
            prefix.setLength(0)
            prefix.append("  ")
        } else {
            prefix.setLength(0)
            prefix.append(if (wemi.boot.WemiUnicodeOutputSupported) "│ " else "| ")
        }

        if (rootIndex == 0) {
            if (rootsSize == 1) {
                result.append(if (wemi.boot.WemiUnicodeOutputSupported) "═ " else "= ")
            } else {
                result.append(if (wemi.boot.WemiUnicodeOutputSupported) "╤ " else "|=")
            }
        } else if (rootIndex + 1 == rootsSize) {
            result.append(if (wemi.boot.WemiUnicodeOutputSupported) "╘ " else "\\=")
        } else {
            result.append(if (wemi.boot.WemiUnicodeOutputSupported) "╞ " else "|=")
        }

        root.println()
    }

    return result
}

/**
 * Tree node for [printTree].
 *
 * @param value of this node
 */
class TreeNode<T>(val value: T) : ArrayList<TreeNode<T>>() {

    fun find(value: T): TreeNode<T>? {
        return this.find { it.value == value }
    }

    operator fun get(value: T): TreeNode<T> {
        return find(value) ?: (TreeNode(value).also { add(it) })
    }
}


/**
 * Returns a pretty-printed string in which the system is displayed as a tree of dependencies.
 * Uses full range of unicode characters for clarity.
 */
fun Map<DependencyId, ResolvedDependency>.prettyPrint(explicitRoots: Collection<DependencyId>?, includeSkipped:Boolean = false): CharSequence {
    /*
    ╤ org.foo:proj:1.0 ✅
    │ ╘ com.bar:pr:2.0 ❌⛔️
    ╞ org.foo:proj:1.0 ✅⤴
    ╘ com.baz:pr:2.0 ❌⛔️

    Status symbols:
    OK ✅
    Error ❌⛔️
    Missing ❓
    Already shown ⤴
     */
    val STATUS_NORMAL: Byte = 0
    val STATUS_NOT_RESOLVED: Byte = 1
    val STATUS_ALREADY_SEEN: Byte = 2

    class NodeData(val dependencyId: DependencyId, var status: Byte, val scope:String?) {
        var optional = false
    }

    val nodes = HashMap<DependencyId, TreeNode<NodeData>>()

    // Build nodes
    for ((depId, resolvedDep) in entries) {
        nodes[depId] = TreeNode(NodeData(depId, STATUS_NORMAL, resolvedDep.scope))
    }

    // Connect nodes (even with cycles)
    val notResolvedNodes = ArrayList<TreeNode<NodeData>>()// ConcurrentModification workaround
    nodes.forEach { (depId, node) ->
        this@prettyPrint[depId]?.dependencies?.forEach { dep ->
            var nodeToConnect = nodes[dep.dependencyId]
            if (nodeToConnect == null) {
                nodeToConnect = TreeNode(NodeData(dep.dependencyId, STATUS_NOT_RESOLVED, null))
                notResolvedNodes.add(nodeToConnect)
            }
            if (dep.optional) {
                nodeToConnect.value.optional = true
            }
            node.add(nodeToConnect)
        }
    }
    for (notResolvedNode in notResolvedNodes) {
        nodes[notResolvedNode.value.dependencyId] = notResolvedNode
    }

    if (!includeSkipped) {
        nodes.entries.removeIf { (id, node) ->
            if (this@prettyPrint[id] == null) {
                true // remove
            } else {
                // remove child nodes that were skipped
                node.removeIf { this@prettyPrint[it.value.dependencyId] == null }

                false // do not remove
            }
        }
    }

    val remainingNodes = HashMap(nodes)

    fun liftNode(dependencyId: DependencyId): TreeNode<NodeData> {
        // Lift what was asked
        val liftedNode = remainingNodes.remove(dependencyId) ?: return TreeNode(NodeData(dependencyId, STATUS_ALREADY_SEEN, null))
        val resultNode = TreeNode(liftedNode.value)
        // Lift all dependencies too and return them in the result node
        for (dependencyNode in liftedNode) {
            resultNode.add(liftNode(dependencyNode.value.dependencyId))
        }
        return resultNode
    }

    val roots = ArrayList<TreeNode<NodeData>>()

    // Lift explicit roots
    explicitRoots?.forEach { root ->
        val liftedNode = liftNode(root)
        // Check for nodes that are in explicitRoots but were never resolved to begin with
        if (liftedNode.value.status == STATUS_ALREADY_SEEN && !this.containsKey(liftedNode.value.dependencyId)) {
            liftedNode.value.status = STATUS_NOT_RESOLVED
        }
        roots.add(liftedNode)
    }

    // Lift implicit roots
    for (key in this.keys) {
        if (remainingNodes.containsKey(key)) {
            roots.add(liftNode(key))
        }
    }

    // Lift rest as roots
    while (remainingNodes.isNotEmpty()) { //This should never happen?
        val (dependencyId, _) = remainingNodes.iterator().next()
        roots.add(liftNode(dependencyId))
    }

    // Now we can start printing!

    return printTree(roots) { result ->
        val dependencyId = this.dependencyId

        result.format(format = Format.Bold)
                .append(dependencyId.group).append(':')
                .append(dependencyId.name).append(':')
                .append(dependencyId.version).format()

        if (dependencyId.classifier != NoClassifier) {
            result.append(":").append(dependencyId.classifier)
        }
        if (dependencyId.type != DEFAULT_TYPE) {
            result.append(":").append(dependencyId.type)
        }

        if (status == STATUS_ALREADY_SEEN) {
            result.append(' ').append(CLI.ICON_SEE_ABOVE)
        } else {
            if (scope != null) {
                result.append(' ').format(Color.Blue).append(scope).format()
            }

            if (optional) {
                result.append(" optional")
            }
            result.append(' ')

            val resolved = this@prettyPrint[dependencyId]

            when {
                resolved == null -> result.append(CLI.ICON_UNKNOWN)
                resolved.hasError -> result.append(CLI.ICON_FAILURE)
                else -> result.append(CLI.ICON_SUCCESS)
            }

            val resolvedFrom = resolved?.resolvedFrom
            if (resolvedFrom != null) {
                result.format(Color.White).append(" from ").format().append(resolvedFrom)
            }

            val log = resolved?.log
            if (log != null && log.isNotBlank()) {
                result.append(' ').format(Color.Red).append(log).format()
            }
        }
    }
}
//endregion

//region Ansi Formatting
private const val ANSI_ESCAPE = '\u001B'

/**
 * Format given char sequence using supplied parameters.
 */
fun format(text: CharSequence, foreground: Color? = null, background: Color? = null, format: Format? = null): CharSequence {
    if (!WemiColorOutputSupported || (foreground == null && background == null && format == null)) return text
    return StringBuilder()
            .format(foreground, background, format)
            .append(text)
            .format()
}

fun StringBuilder.format(foreground: Color? = null, background: Color? = null, format: Format? = null):StringBuilder {
    if (!WemiColorOutputSupported) return this

    append("$ANSI_ESCAPE[0") //Reset everything first
    if (foreground != null) {
        append(';')
        append(30 + foreground.offset)
    }
    if (background != null) {
        append(';')
        append(40 + background.offset)
    }
    if (format != null) {
        append(';')
        append(format.number)
    }
    append('m')
    return this
}

/**
 * Color for ANSI formatting
 */
enum class Color(internal val offset: Int) {
    Black(0), // Significant
    Red(1), // Error
    Green(2), // Label
    Yellow(3), // Suggestion
    Blue(4), // Value
    Magenta(5), // (Cache)
    Cyan(6), // Time
    White(7) // Not significant
}

/**
 * Format for ANSI formatting
 */
enum class Format(internal val number: Int) {
    Bold(1), // Label or Prompt
    Underline(4), // Input
}
//endregion

//region Miscellaneous
/**
 * Find the value:[V] that corresponds to the [key].
 * If [key] directly is not in the map, search the map once again, but ignore case.
 *
 * @return null if key not found
 */
internal fun <V> Map<String, V>.findCaseInsensitive(key: String): V? {
    synchronized(this) {
        return getOrElse(key) {
            for ((k, v) in this) {
                if (key.equals(k, ignoreCase = true)) {
                    return v
                }
            }
            return null
        }
    }
}

/**
 * @return true if the string is a valid identifier, using Java identifier rules
 */
internal fun String.isValidIdentifier(): Boolean {
    if (isEmpty()) {
        return false
    }
    if (!this[0].isJavaIdentifierStart()) {
        return false
    }
    for (i in 1..lastIndex) {
        if (!this[i].isJavaIdentifierPart()) {
            return false
        }
    }
    return true
}

/**
 * Parse Java version in form of N or 1.N where N is a number.
 *
 * @return N or null if invalid
 */
internal fun parseJavaVersion(version:String?):Int? {
    return version?.removePrefix("1.")?.toIntOrNull()
}

/**
 * Adds all items from [items] to receiver, like [MutableList.addAll], but in reverse order.
 * `internal` because not generic.
 */
internal fun <T> ArrayList<T>.addAllReversed(items:ArrayList<T>) {
    ensureCapacity(items.size)
    for (i in items.indices.reversed()) {
        add(items[i])
    }
}
//endregion