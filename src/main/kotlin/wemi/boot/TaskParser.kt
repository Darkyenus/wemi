package wemi.boot

import org.jline.reader.ParsedLine
import org.jline.reader.Parser
import wemi.util.*

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
        private val parsed = PartitionedLine(listOf(line), true, false)

        val words:List<String>
        val tokenTypes:List<TokenType?>
        val positionWord:Int
        private val positionInWord:Int

        /**
         * Position tries to hold on to the letter of which it is an index in the sources.
         * If position is less than 0, resulting position will be zero.
         * If the letter does not exist in the resulting tokens, it travels forward up to the next character
         * that is in the result. If there is no such character (end of the line), position is the last valid index.
         */
        init {
            var positionWord = 0
            var positionInWord = 0
            val words = ArrayList<String>()
            val tokenTypes = ArrayList<TokenType?>()

            for (part in parsed.parts) {
                if (part.meaning == TokenType.Whitespace) {
                    continue
                }

                if (part.start < cursor) {
                    positionWord = words.size
                    positionInWord = cursor - part.start
                }

                words.add(part.text)
                tokenTypes.add(part.meaning)
            }

            //TODO Check that position behaves well even in corner cases described in docs

            this.words = words
            this.tokenTypes = tokenTypes
            this.positionWord = positionWord
            this.positionInWord = positionInWord
        }

        override fun words(): List<String> = words

        override fun cursor(): Int = cursor

        override fun word(): String {
            return words.getOrElse(positionWord) { "" }
        }

        override fun wordCursor(): Int = positionInWord

        override fun wordIndex(): Int = positionWord

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


    enum class TokenType {
        Project,
        Configuration,
        Key,
        InputKey,
        Input,

        // Lexer types
        Separator,
        Whitespace
    }

    class Part(val text:String, val start:Int, val end:Int) {
        var meaning:TokenType? = null
            set(value) {
                when (field) {
                    TokenType.Separator, TokenType.Whitespace -> {}
                    else -> field = value
                }
            }
    }

    class PartitionedLine(sources: Collection<String>,
                          allowQuotes: Boolean,
                          machineReadable: Boolean) {

        val parts:List<Part> = createParts(sources, allowQuotes)
        val tasks:List<Task> = parseTasks(machineReadable)

        //region Lexing
        /**
         * Parse tokens from the given un-preprocessed line.
         * Quotes ("), escapes (\<anything>) and separators are handled.
         *
         * Escape character removes any control value from any following character (even if it does not have any).
         * Quoting a string (surrounding it with ") will disable breaking the word on whitespace.
         *
         * Escape on end of the line is treated as a regular character, so are unmatched quotes.
         */
        private fun createParts(sources: Collection<String>, allowQuotes: Boolean):List<Part> {
            val parts = ArrayList<Part>()
            var indexBase = 0

            var partStart = indexBase
            var partWhitespace = true
            val partText = StringBuilder()
            var currentQuoted = false
            var escapeNext = false

            fun endWord(index:Int):Part? {
                val startIndex = partStart
                val endIndex = indexBase + index

                // Force close quotes
                if (currentQuoted) {
                    currentQuoted = false
                }

                // Ignore broken escape
                if (escapeNext) {
                    escapeNext = false
                    partText.append('\\')
                }

                var part: Part? = null

                // Omit 0-width whitespace
                if (!(startIndex == endIndex && partWhitespace)) {
                    part = Part(partText.toString(), startIndex, endIndex)
                    if (partWhitespace) {
                        part.meaning = TokenType.Whitespace
                    }
                    parts.add(part)
                }

                partText.setLength(0)
                partStart = endIndex
                partWhitespace = true

                return part
            }

            for (source in sources) {
                source.forCodePointsIndexed { index, cp ->
                    when {
                        escapeNext -> {
                            escapeNext = false
                            if (partWhitespace) {
                                // It is not whitespace anymore, end the word
                                endWord(index - 1)//Ended before backslash
                            }
                            partWhitespace = false
                            partText.appendCodePoint(cp)
                        }
                        cp == '\\'.toInt() -> // Escape next character
                            escapeNext = true
                        currentQuoted -> {
                            if (cp == '"'.toInt()) {
                                // End quotes
                                currentQuoted = false
                                endWord(index)
                            } else {
                                // Continue quotes
                                partText.appendCodePoint(cp)
                            }
                        }
                        allowQuotes && cp == '"'.toInt() -> { // Start quotes
                            endWord(index)
                            partWhitespace = false
                            currentQuoted = true
                        }
                        SEPARATORS.contains(cp) -> {
                            // Separator!
                            // End current word
                            endWord(index)
                            // Create word with just the separator
                            partText.appendCodePoint(cp)
                            partWhitespace = false
                            endWord(index + 1)?.meaning = TokenType.Separator
                        }
                        else -> { // Any other normal character
                            val ws = Character.isWhitespace(cp)
                            when {
                                ws == partWhitespace ->
                                    // Keeping the type
                                    partText.appendCodePoint(cp)
                                ws -> {
                                    // Whitespace just started
                                    endWord(index)
                                    partText.appendCodePoint(cp)
                                }
                                else -> {
                                    // Whitespace just ended
                                    endWord(index)
                                    partText.appendCodePoint(cp)
                                    partWhitespace = false
                                }
                            }
                        }
                    }
                }

                // Finish last word
                endWord(source.length)

                indexBase += source.length
            }

            return parts
        }
        //endregion

        //region Errors
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
                val SPREAD = 4
                if (word - SPREAD - 1 in parts.indices) {
                    sb.append("...")
                }
                for (w in word-SPREAD .. word+SPREAD) {
                    if (w !in parts.indices) {
                        continue
                    }
                    if (w == 0) {
                        sb.append('>')
                        if (colored) {
                            sb.format(format = Format.Underline).append(parts[w].text).format()
                        } else {
                            sb.append(parts[w].text)
                        }
                        sb.append('<')
                    } else {
                        sb.append(parts[w].text)
                    }
                }
                if (word + SPREAD + 1 in parts.indices) {
                    sb.append("...")
                }

                if (sb.isEmpty()) {
                    sb.append("(At word ").append(word).append('/').append(parts.size).append(")")
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
            lazyErrors!!.add(parsePosition to message)
        }
        //endregion

        //region Parsing
        private var parsePosition = 0

        private fun nextPart(type: TokenType?):Part? {
            if (parsePosition >= parts.size) {
                return null
            }
            return parts[parsePosition++].apply { meaning = type }
        }

        private fun hasNextPart():Boolean {
            return parsePosition < parts.size
        }

        private fun takeUpToSeparator(type:TokenType?, onlyBadSeparator:String?):String? {
            val partsFrom = parsePosition
            var partsUntil = parsePosition
            while (true) {
                val mark = parsePosition
                val part = nextPart(type)

                if (part == null || part.meaning == TokenType.Whitespace) {
                    // Part is not ok, throw it away
                    parsePosition = mark
                    break
                }

                if (part.meaning == TokenType.Separator) {
                    if (onlyBadSeparator == null) {
                        // All separators are bad, not ok, throw it away
                        parsePosition = mark
                        break
                    } else if (onlyBadSeparator == part.text) {
                        // This specific separator is bad, not ok, throw it away
                        parsePosition = mark
                        break
                    }
                }

                // Part is ok, advance partsUntil
                partsUntil = parsePosition
            }

            when {
                partsFrom == partsUntil ->
                    return null
                partsFrom + 1 == partsUntil ->
                    return parts[partsFrom].text
                else -> {
                    val sb = StringBuilder()
                    for (i in partsFrom until partsUntil) {
                        sb.append(parts[i].text)
                    }
                    return sb.toString()
                }
            }
        }

        private fun consumeWhitespaceParts() {
            while (true) {
                val mark = parsePosition
                val part = nextPart(null)
                if (part == null || part.meaning != TokenType.Whitespace) {
                    parsePosition = mark
                    break
                }
            }
        }

        private fun matchSeparator(separatorText:String):Boolean {
            val mark = parsePosition
            val next = nextPart(null)
            if (next == null || next.meaning != TokenType.Separator || next.text != separatorText) {
                parsePosition = mark
                return false
            }
            return true
        }

        private fun matchSuffixedIdentifier(suffixToken: String, identifierType: TokenType): String? {
            val mark = parsePosition
            val identifier = takeUpToSeparator(identifierType, null)
            if (identifier == null || !identifier.isValidIdentifier()) {
                parsePosition = mark
                return null
            }
            consumeWhitespaceParts()
            if (matchSeparator(suffixToken)) {
                return identifier
            } else {
                parsePosition = mark
                return null
            }
        }

        private fun matchTask(machineReadable: Boolean): Task? {
            var flags = 0

            consumeWhitespaceParts()
            val project = matchSuffixedIdentifier(PROJECT_SEPARATOR, TokenType.Project)
            consumeWhitespaceParts()
            val configurations = ArrayList<String>()
            while (true) {
                configurations.add(matchSuffixedIdentifier(CONFIGURATION_SEPARATOR, TokenType.Configuration) ?: break)
                consumeWhitespaceParts()
            }

            val keyRawText = takeUpToSeparator(TokenType.Key, null)

            if (keyRawText == null) {
                error("Key name expected")
                return null
            }

            val key = run {
                var start = 0
                var end = keyRawText.length

                if (machineReadable) {
                    if (keyRawText.startsWith('#')) {
                        start++
                        flags = flags or Task.FLAG_MACHINE_READABLE_COMMAND
                    }
                    if (keyRawText.endsWith('?')) {
                        end--
                        flags = flags or Task.FLAG_MACHINE_READABLE_OPTIONAL
                    }
                }

                keyRawText.substring(start, end)
            }

            if (!key.isValidIdentifier()) {
                error("Key is not a valid identifier")
                return null
            }

            consumeWhitespaceParts()

            val input = ArrayList<Pair<String?, String>>()

            while (hasNextPart()) {
                if (matchSeparator(TASK_SEPARATOR)) {
                    break
                }

                val inputKeyOrNull = matchSuffixedIdentifier(INPUT_SEPARATOR, TokenType.InputKey)
                consumeWhitespaceParts()

                val value = takeUpToSeparator(TokenType.Input, TASK_SEPARATOR)
                consumeWhitespaceParts()

                assert(inputKeyOrNull != null || value != null)

                input.add(inputKeyOrNull to (value ?: ""))
            }

            return Task(project, configurations, key, input, flags)
        }

        private fun parseTasks(machineReadable: Boolean = false): List<Task> {
            val tasks = ArrayList<Task>()

            while (hasNextPart()) {
                val task = matchTask(machineReadable)
                if (task != null) {
                    tasks.add(task)
                }
            }

            return tasks
        }
        //endregion
    }
}