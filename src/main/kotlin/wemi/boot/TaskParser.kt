package wemi.boot

import org.jline.reader.ParsedLine
import org.jline.reader.Parser
import wemi.util.*
import java.util.*

/**
 * Parses task lines.
 *
 * Task line syntax:
 *
 * <line> := <command>
 *         | <line> ';' <command>
 *
 * <command> := <scoped-task> <input>*
 *
 * <scoped-task> := (<project> '/')? (<configuration> ':')* <task>
 *
 * <input> := <named-input> | <free-input>
 * <named-input> := <input-key> '=' <input-text>
 * <free-input> := <input-text>
 *
 * <project>, <configuration>, <task>, <input-key> := Valid Java identifier
 * <input-text> := Arbitrary text
 */
object TaskParser : Parser {

    override fun parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedTaskLine {
        return ParsedTaskLine(line, cursor)
    }

    class ParsedTaskLine(val line: String, val cursor: Int) : ParsedLine {
        val parsed = parseTokens(line, cursor)

        override fun words(): List<String> = parsed.tokens

        override fun cursor(): Int = cursor

        override fun word(): String {
            return parsed.tokens.getOrElse(parsed.positionWord) { "" }
        }

        override fun wordCursor(): Int = parsed.positionInWord

        override fun wordIndex(): Int = parsed.positionWord

        override fun line(): String = line
    }

    const val PROJECT_SEPARATOR = "/"
    const val CONFIGURATION_SEPARATOR = ":"
    const val INPUT_SEPARATOR = "="
    const val TASK_SEPARATOR = ";"

    private val SEPARATORS = intArrayOf(
            PROJECT_SEPARATOR[0].toInt(),
            CONFIGURATION_SEPARATOR[0].toInt(),
            INPUT_SEPARATOR[0].toInt(),
            TASK_SEPARATOR[0].toInt())

    fun createTokens(parsedTokens: List<String>): Tokens<String, TokenType> {
        var limit = parsedTokens.size
        if (limit > 0 && parsedTokens.last().isBlank()) {
            limit--
        }

        return Tokens(parsedTokens, limit, TokenType.Other)
    }

    /**
     * Returned by [parseTokens].
     *
     * @param positionWord index of the word pointed at by the 'position' in [tokens]
     * @param positionInWord index of the letter pointed at by the 'position' in [tokens][[positionWord]].
     */
    class MarkedLine(val tokens: List<String>, val positionWord: Int, val positionInWord: Int) {

        private var _tokenTypes: Array<TokenType>? = null
        private var _tasks: List<Task>? = null

        private fun initialize() {
            if (_tokenTypes != null) {
                return
            }

            val tokens = createTokens(this.tokens)
            _tasks = parseTasks(tokens)
            _tokenTypes = tokens.memos
        }

        val tokenTypes: Array<TokenType>
            get() {
                if (_tokenTypes == null) {
                    initialize()
                }
                return _tokenTypes!!
            }

        val tasks: List<Task>
            get() {
                if (_tasks == null) {
                    initialize()
                }
                return _tasks!!
            }
    }

    private fun Tokens<String, TokenType>.matchSuffixedIdentifier(suffixToken: String, identifierType: TokenType): String? {
        if (matches { it.isValidIdentifier() } && matches(skip = 1, value = suffixToken)) {
            val identifier = next(identifierType)
            // Skip suffix
            next(TokenType.Other)
            return identifier
        }
        return null
    }

    private fun Tokens<String, TokenType>.matchTask(machineReadable: Boolean): Task? {
        var flags = 0

        val project = matchSuffixedIdentifier(PROJECT_SEPARATOR, TokenType.Project)
        val configurations = ArrayList<String>()
        while (true) {
            configurations.add(matchSuffixedIdentifier(CONFIGURATION_SEPARATOR, TokenType.Configuration) ?: break)
        }

        if (!hasNext(TokenType.Key)) {
            error("Key name expected")
            return null
        }

        val key = peek()!!.let { key ->
            var start = 0
            var end = key.length

            if (machineReadable) {
                if (key.startsWith('#')) {
                    start++
                    flags = flags or Task.FLAG_MACHINE_READABLE_COMMAND
                }
                if (key.endsWith('?')) {
                    end--
                    flags = flags or Task.FLAG_MACHINE_READABLE_OPTIONAL
                }
            }

            key.substring(start, end)
        }

        if (!key.isValidIdentifier()) {
            error("Key is not a valid identifier")
            return null
        }
        next(TokenType.Key) // Consume the token as key

        val input = ArrayList<Pair<String?, String>>()

        while (hasNext(TokenType.Other) && !matches(value = TASK_SEPARATOR)) {
            val inputKey = matchSuffixedIdentifier(INPUT_SEPARATOR, TokenType.InputKey)
            if (!hasNext()) {
                error("Expected input for key $inputKey")
                return null
            }
            val value = next(TokenType.Input)!!

            input.add(inputKey to value)
        }

        return Task(project, configurations, key, input, flags)
    }

    fun parseTasks(tokens: Tokens<String, TokenType>, machineReadable: Boolean = false): List<Task> {
        val tasks = ArrayList<Task>()

        tokens.run {

            if (!hasNext()) {
                return@run
            }

            while (true) {
                val task = matchTask(machineReadable)
                if (task != null) {
                    tasks.add(task)
                }

                if (hasNext()) {
                    if (match(TASK_SEPARATOR)) {
                        continue
                    }

                    error("Expected $TASK_SEPARATOR")
                }

                break
            }
        }

        return tasks
    }

    enum class TokenType {
        Project,
        Configuration,
        Key,
        InputKey,
        Input,
        Other
    }

    /**
     * Parse tokens from the given un-preprocessed line.
     * Quotes ("), escapes (\<anything>) and separators are handled.
     * Also, position into the line is translated.
     *
     * Escape character removes any control value from any following character (even if it does not have any).
     * Quoting a string (surrounding it with ") will disable breaking the word on whitespace.
     *
     * Escape on end of the line is treated as a regular character, so are unmatched quotes.
     *
     * Position tries to hold on to the letter of which it is an index in the [line].
     * If position is less than 0, resulting position will be zero.
     * If the letter does not exist in the resulting tokens, it travels forward up to the next character
     * that is in the result. If there is no such character (end of the line), position is the last valid index.
     */
    fun parseTokens(line: String, position: Int): MarkedLine {
        val words = ArrayList<String>()

        var positionWord = 0
        var positionInWord = 0

        var currentQuoted = false
        val current = StringBuilder()
        var escapeNext = false

        fun endWord(force: Boolean) {
            if (!force && current.isBlank()) {
                // We will not add a word made out of whitespace unless explicitly asked to
                if (positionWord == words.size) {
                    // The cursor was somewhere inside the blank word.
                    // The word has been omitted, so the position will fall on the first character of the next word.
                    positionInWord = 0
                }
                return
            }

            words.add(current.toString())
            current.setLength(0)
        }

        line.forCodePointsIndexed { index, cp ->
            if (index == position) {
                positionWord = words.size
                positionInWord = current.length
            }

            when {
                escapeNext -> {
                    escapeNext = false
                    current.appendCodePoint(cp)
                }
                cp == '\\'.toInt() -> // Escape next character
                    escapeNext = true
                currentQuoted -> {
                    if (cp == '"'.toInt()) {
                        currentQuoted = false
                        endWord(true)
                    } else {
                        current.appendCodePoint(cp)
                    }
                }
                cp == '"'.toInt() -> // Start quotes
                    currentQuoted = true
                Character.isWhitespace(cp) -> // Word end
                    endWord(false)
                SEPARATORS.contains(cp) -> {
                    // Separator!
                    // End current word
                    endWord(false)
                    // Create word with just the separator
                    current.appendCodePoint(cp)
                    endWord(true)
                }
                else -> // Any other normal character
                    current.appendCodePoint(cp)
            }
        }

        // Ignore invalid controls
        if (currentQuoted) {
            currentQuoted = false
            current.insert(0, '"')
        }

        if (escapeNext) {
            escapeNext = false
            current.append('\\')
        }

        // Finish last word
        endWord(true)

        // When position is end of line or after, use the position on the end
        if (position >= line.length) {
            positionWord = words.size - 1
            positionInWord = words.last().length
        }

        return MarkedLine(words, positionWord, positionInWord)
    }

    /**
     * Parse command line arguments into tokens.
     * This is similar to parsing it from single line, but it is assumed that quotes and whitespace are already
     * resolved, so these aspects are not handled and argument boundaries are treated as word separators.
     */
    fun parseTokens(arguments: List<String>): List<String> {
        val words = ArrayList<String>()

        val current = StringBuilder()
        var escapeNext = false

        fun endWord(force: Boolean) {
            if (!force && current.isBlank()) {
                // We will not add a word made out of whitespace unless explicitly asked to
                return
            }

            words.add(current.toString())
            current.setLength(0)
        }

        for (argument in arguments) {
            // Arguments are already whitespace-separated, so we don't have to do that.
            // Since whitespace separation is also already done, we don't have to process quotes.
            // And when quotes are not a concern, escaping is only for separators, which is what we care for.

            argument.forCodePoints { cp ->
                when {
                    escapeNext -> {
                        escapeNext = false
                        current.appendCodePoint(cp)
                    }
                    cp == '\\'.toInt() -> // Escape next character
                        escapeNext = true
                    SEPARATORS.contains(cp) -> {
                        // Separator!
                        // End current word
                        endWord(false)
                        // Create word with just the separator
                        current.appendCodePoint(cp)
                        endWord(true)
                    }
                    else -> // Any other normal character
                        current.appendCodePoint(cp)
                }
            }

            // Ignore invalid controls
            if (escapeNext) {
                escapeNext = false
                current.append('\\')
            }

            endWord(argument.isEmpty())
        }

        return words
    }

}

class Tokens<T, Memo>(val data: List<T>, private val limit: Int = data.size, defaultMemo: Memo? = null) {

    @Suppress("UNCHECKED_CAST")
    val memos: Array<Memo>? = if (defaultMemo == null) {
        null
    } else {
        val memoArray: Array<Memo> = java.lang.reflect.Array.newInstance((defaultMemo as Any)::class.java, data.size) as Array<Memo>
        Arrays.fill(memoArray, defaultMemo)
        memoArray
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