package wemi.util

import com.darkyen.tproll.util.TerminalColor
import com.esotericsoftware.jsonbeans.Json
import com.esotericsoftware.jsonbeans.JsonValue
import java.util.*

/**
 * Find the value:[V] that corresponds to the [key].
 * If [key] directly is not in the map, search the map once again, but ignore case.
 *
 * @return null if key not found
 */
fun <V> Map<String, V>.findCaseInsensitive(key: String): V? {
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
 * Format given [ms] duration as a human readable duration string.
 *
 * Example output: "1 day 5 minutes 33 seconds 0 ms"
 */
fun formatTimeDuration(ms: Long): CharSequence {
    val Second = 1000
    val Minute = Second * 60
    val Hour = Minute * 60
    val Day = Hour * 24

    val result = StringBuilder()
    var remaining = ms

    val days = remaining / Day
    remaining %= Day
    val hours = remaining / Hour
    remaining %= Hour
    val minutes = remaining / Minute
    remaining %= Minute
    val seconds = remaining / Second
    remaining %= Second

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
inline fun CharSequence.forCodePointsIndexed(action: (Index, CodePoint) -> Unit) {
    val length = this.length
    var i = 0

    while (i < length) {
        val c1 = get(i++)
        if (!Character.isHighSurrogate(c1) || i >= length) {
            action(i, c1.toInt())
        } else {
            val c2 = get(i)
            if (Character.isLowSurrogate(c2)) {
                i++
                action(i, Character.toCodePoint(c1, c2))
            } else {
                action(i, c1.toInt())
            }
        }
    }
}

/**
 * @see [forCodePointsIndexed] but ignores the index
 */
inline fun CharSequence.forCodePoints(action: (CodePoint) -> Unit) {
    forCodePointsIndexed { _, cp ->
        action(cp)
    }
}

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
fun CharSequence.toSafeFileName(replacement: Char = '_'): CharSequence {
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

/**
 * @return true if the string is a valid identifier, using Java identifier rules
 */
fun String.isValidIdentifier(): Boolean {
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
 * Return a new array of type Array<[T]> that contains [size] elements that are [with].
 */
@Suppress("UNCHECKED_CAST")
fun <T> arrayFilledWith(size: Int, with: T): Array<T> {
    val memoArray: Array<T> = java.lang.reflect.Array.newInstance((with as Any)::class.java, size) as Array<T>
    Arrays.fill(memoArray, with)
    return memoArray
}

typealias Mark = Int

class Tokens<T, Memo>(val data: List<T>, private val limit: Int = data.size, defaultMemo: Memo? = null) {

    @Suppress("UNCHECKED_CAST")
    val memos: Array<Memo>? = if (defaultMemo == null) {
        null
    } else {
        arrayFilledWith(data.size, defaultMemo)
    }

    private var next = 0
    private var lazyErrors: ArrayList<Pair<Int, String>>? = null

    val errors: List<Pair<Int, String>>
        get() = lazyErrors ?: emptyList()

    fun formattedErrors(colored: Boolean): Iterator<String> = object : Iterator<String> {
        val parent = errors.iterator()

        override fun hasNext(): Boolean {
            return parent.hasNext()
        }

        override fun next(): String {
            val (word, message) = parent.next()

            val sb = StringBuilder()
            if (word - 3 in data.indices) {
                sb.append("... ")
            }
            if (word - 2 in data.indices) {
                sb.append(data[word - 2]).append(' ')
            }
            if (word - 1 in data.indices) {
                sb.append(data[word - 1]).append(' ')
            }
            if (word in data.indices) {
                sb.append('>')
                if (colored) {
                    sb.append(format(data[word].toString(), format = Format.Underline))
                } else {
                    sb.append(data[word])
                }
                sb.append('<')

                sb.append(' ')
            }
            if (word + 1 in data.indices) {
                sb.append(data[word + 1]).append(' ')
            }
            if (word + 2 in data.indices) {
                sb.append(data[word + 2]).append(' ')
            }
            if (word + 3 in data.indices) {
                sb.append("... ")
            }

            if (sb.isEmpty()) {
                sb.append("(At word ").append(word).append('/').append(data.size).append(")")
            } else {
                // Strip leading space
                sb.setLength(sb.length - 1)
            }
            sb.append('\n').append('⤷').append(' ')
            if (colored) {
                sb.append(format(message, Color.Red))
            } else {
                sb.append(message)
            }

            return sb.toString()
        }
    }

    fun mark(): Mark = next

    fun Mark.rollback() {
        next = this
    }

    fun Mark.errorAndRollback(error: String) {
        error(error)
        next = this
    }

    fun error(message: String) {
        if (lazyErrors == null) {
            lazyErrors = ArrayList()
        }
        lazyErrors!!.add(next to message)
    }

    fun hasNext(memo: Memo? = null): Boolean {
        if (next < data.size && memos != null && memo != null) {
            memos[next] = memo
        }

        return next < limit
    }

    fun next(memo: Memo? = null): T? {
        if (next < data.size && memos != null && memo != null) {
            memos[next] = memo
        }

        return if (next < limit) {
            data[next++]
        } else {
            null
        }
    }

    fun peek(skip: Int = 0): T? {
        return data.getOrNull(next + skip)
    }

    fun match(memo: Memo? = null, matcher: (T) -> Boolean): Boolean {
        return if (next < limit && matcher(data[next])) {
            if (memos != null && memo != null) {
                memos[next] = memo
            }
            next++
            true
        } else {
            false
        }
    }

    fun matches(skip: Int = 0, matcher: (T) -> Boolean): Boolean {
        val index = next + skip
        return index in (0 until limit) && matcher(data[index])
    }

    fun match(value: T, memo: Memo? = null): Boolean {
        return if (next < limit && value == data[next]) {
            if (memos != null && memo != null) {
                memos[next] = memo
            }
            next++
            true
        } else {
            false
        }
    }

    fun matches(skip: Int = 0, value: T): Boolean {
        val index = next + skip
        return index in (0 until limit) && value == data[index]
    }
}

fun JsonValue?.putArrayStrings(into: MutableCollection<String>) {
    this?.forEach {
        into.add(it.asString())
    }
}

fun Json.writeStringArray(from: Collection<String>, name: String, skipEmpty: Boolean = false) {
    if (from.isNotEmpty() || !skipEmpty) {
        writeArrayStart(name)
        for (s in from) {
            writeValue(s as Any, String::class.java)
        }
        writeArrayEnd()
    }
}

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
                result.append("╘ ")
                prefix.append("  ")
            } else {
                result.append("╞ ")
                prefix.append("│ ")
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
            prefix.append("│ ")
        }

        if (rootIndex == 0) {
            if (rootsSize == 1) {
                result.append("═ ")
            } else {
                result.append("╤ ")
            }
        } else if (rootIndex + 1 == rootsSize) {
            result.append("╘ ")
        } else {
            result.append("╞ ")
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

const val ANSI_ESCAPE = '\u001B'

/**
 * Format given char sequence using supplied parameters.
 */
fun format(text: CharSequence, foreground: Color? = null, background: Color? = null, format: Format? = null): CharSequence {
    if (!TerminalColor.COLOR_SUPPORTED || (foreground == null && background == null && format == null)) return text
    return StringBuilder()
            .format(foreground, background, format)
            .append(text)
            .format()
}

fun StringBuilder.format(foreground: Color? = null, background: Color? = null, format: Format? = null):StringBuilder {
    if (!TerminalColor.COLOR_SUPPORTED) return this

    if (foreground == null && background == null && format == null) {
        append("$ANSI_ESCAPE[0m")
    } else {
        append("$ANSI_ESCAPE[")
        if (foreground != null) {
            append(30 + foreground.offset)
            append(';')
        }
        if (background != null) {
            append(40 + background.offset)
            append(';')
        }
        if (format != null) {
            append(format.number)
            append(';')
        }
        setCharAt(length - 1, 'm')
    }
    return this
}

/**
 * Color for ANSI formatting
 */
enum class Color(internal val offset: Int) {
    Black(0),
    Red(1), // Error
    Green(2), // Label
    Yellow(3), // Suggestion
    Blue(4), // Value
    Magenta(5),
    Cyan(6), // Time
    White(7)
}

/**
 * Format for ANSI formatting
 */
enum class Format(internal val number: Int) {
    Bold(1), // Label or Prompt
    Underline(4), // Input
}