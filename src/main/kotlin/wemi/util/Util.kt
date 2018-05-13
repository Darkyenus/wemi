package wemi.util

import com.darkyen.tproll.util.PrettyPrinter
import org.slf4j.LoggerFactory
import wemi.Key
import wemi.boot.WemiColorOutputSupported
import java.nio.file.Files
import java.nio.file.Path
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
fun StringBuilder.appendTimeDuration(ms: Long): StringBuilder {
    val Second = 1000
    val Minute = Second * 60
    val Hour = Minute * 60
    val Day = Hour * 24

    val result = this
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
 * Format given [bytes] amount as a human readable duration string.
 * Uses SI units. Only two most significant units are used, rest is truncated.
 *
 * Example output: "1 day 5 minutes 33 seconds 0 ms"
 */
fun StringBuilder.appendByteSize(bytes: Long): StringBuilder {
    val Kilo = 1000L
    val Mega = 1000_000L
    val Giga = 1000_000_000L
    val Tera = 1000_000_000_000L

    var remaining = bytes

    val tera = remaining / Tera
    remaining %= Tera
    val giga = remaining / Giga
    remaining %= Giga
    val mega = remaining / Mega
    remaining %= Mega
    val kilo = remaining / Kilo
    remaining %= Kilo

    val R = 2
    var relevant = R

    if ((tera > 1L || relevant < R) && relevant > 0) {
        append(tera).append(" TB ")
        relevant--
    }

    if ((giga > 1L || relevant < R) && relevant > 0) {
        append(giga).append(" GB ")
        relevant--
    }

    if ((mega > 1L || relevant < R) && relevant > 0) {
        append(mega).append(" MB ")
        relevant--
    }

    if ((kilo > 1L || relevant < R) && relevant > 0) {
        append(kilo).append(" kB ")
        relevant--
    }

    if (relevant > 0) {
        append(remaining).append(" B ")
    }

    setLength(length-1)//Truncate trailing space

    return this
}

/**
 * Append given [character] multiple [times]
 */
fun StringBuilder.appendTimes(character:Char, times:Int):StringBuilder {
    if (times <= 0) {
        return this
    }
    ensureCapacity(times)
    for (i in 0 until times) {
        append(character)
    }
    return this
}

/**
 * Append given [text] centered in [width], padded by [padding]
 */
fun StringBuilder.appendCentered(text:String, width:Int, padding:Char):StringBuilder {
    val padAmount = width - text.length
    if (padAmount <= 0) {
        return append(text)
    }

    val leftPad = padAmount / 2
    val rightPadding = padAmount - leftPad
    return appendTimes(padding, leftPad).append(text).appendTimes(padding, rightPadding)
}

/**
 * Append given [number], prefixed with [padding] to take up at least [width].
 */
fun StringBuilder.appendPadded(number:Int, width:Int, padding:Char):StringBuilder {
    val originalLength = length
    append(number)
    while (length < originalLength + width) {
        insert(originalLength, padding)
    }
    return this
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

const val ANSI_ESCAPE = '\u001B'

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
    Black(0),
    Red(1), // Error
    Green(2), // Label
    Yellow(3), // Suggestion
    Blue(4), // Value
    Magenta(5), // (Cache)
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

private fun StringBuilder.appendPrettyValue(value:Any?):StringBuilder {
    if (value is WithDescriptiveString) {
        val valueText = value.toDescriptiveAnsiString()
        if (valueText.contains(ANSI_ESCAPE)) {
            this.append(valueText)
        } else {
            this.format(Color.Blue).append(valueText).format()
        }
    } else {
        this.format(Color.Blue)
        PrettyPrinter.append(this, value)
        if (value is Function<*>) {
            val javaClass = value.javaClass
            this.format(Color.White).append(" (").append(javaClass.name).append(')')
        } else if (value is Path || value is LocatedPath) {
            val path = value as? Path ?: (value as LocatedPath).file

            if (Files.isRegularFile(path)) {
                try {
                    val size = Files.size(path)
                    this.format(Color.White).append(" (").appendByteSize(size).append(')')
                } catch (ignored:Exception) {}
            }
        }
        this.format()
    }
    return this
}

private val APPEND_KEY_RESULT_LOG = LoggerFactory.getLogger("AppendKeyresult")

/**
 * Append the [value] formatted like the result of the [key] and newline.
 */
fun <Value> StringBuilder.appendKeyResultLn(key: Key<Value>, value:Value) {
    val prettyPrinter = key.prettyPrinter

    if (prettyPrinter != null) {
        val printed: CharSequence? =
                try {
                    prettyPrinter(value)
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
        is Collection<*> -> {
            for ((i, item) in value.withIndex()) {
                this.format(Color.White).append(i+1).append(": ").format().appendPrettyValue(item).append('\n')
            }
        }
        is Array<*> -> {
            for ((i, item) in value.withIndex()) {
                this.format(Color.White).append(i+1).append(": ").format().appendPrettyValue(item).append('\n')
            }
        }
        else -> {
            this.appendPrettyValue(value).append('\n')
        }
    }
}

/**
 * Parse Java version in form of N or 1.N where N is a number.
 *
 * @return N or null if invalid
 */
fun parseJavaVersion(version:String?):Int? {
    return version?.removePrefix("1.")?.toIntOrNull()
}