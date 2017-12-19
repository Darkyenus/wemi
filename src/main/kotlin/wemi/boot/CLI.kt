package wemi.boot

import com.darkyen.tproll.util.PrettyPrinter
import com.darkyen.tproll.util.TerminalColor
import org.jline.reader.*
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.slf4j.LoggerFactory
import wemi.*
import wemi.util.*
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Handles user interaction in standard (possibly interactive) mode
 */
object CLI {

    private val LOG = LoggerFactory.getLogger(javaClass)

    private val Terminal: Terminal by lazy { TerminalBuilder.terminal() }

    private val LineReader: LineReaderImpl by lazy {
        LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(DefaultParser().apply {
                    isEofOnEscapedNewLine = false
                    isEofOnUnclosedQuote = false
                })
                .history(getHistory("repl"))
                .build() as LineReaderImpl
    }

    internal val InputLineReader: LineReaderImpl by lazy {
        LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(DefaultParser().apply {
                    isEofOnEscapedNewLine = false
                    isEofOnUnclosedQuote = false
                })
                .history(NoHistory)
                .build() as LineReaderImpl
    }

    private var defaultProject: Project? = null
        set(value) {
            if (value != null) {
                print(formatLabel("Project: "))
                println(formatValue(value.name))
            }
            field = value
        }

    internal fun findDefaultProject(root: File):Project? {
        val allProjects = AllProjects
        when {
            allProjects.isEmpty() -> return null
            allProjects.size == 1 -> return allProjects.values.first()
            else -> {
                var closest: Project? = null
                for (project in allProjects.values) {
                    when {
                        project.projectRoot == root -> return project
                        closest == null -> closest = project
                        else -> // Compare how close they are!
                            try {
                                val projectDist = project.projectRoot.toRelativeString(root).count { it == File.pathSeparatorChar }
                                val closestDist = closest.projectRoot.toRelativeString(root).count { it == File.pathSeparatorChar }
                                if (projectDist < closestDist) {
                                    closest = project
                                }
                            } catch (_: IllegalArgumentException) {
                                //May happen if projects are on different roots, but lets not deal with that.
                            }
                    }
                }
                return closest
            }
        }
    }

    internal fun init(root: File) {
        val shouldBeDefault = findDefaultProject(root)
        if (shouldBeDefault == null) {
            printWarning("No project found")
        } else {
            defaultProject = shouldBeDefault
        }

        val out = Terminal.writer()
        System.setOut(PrintStream(object : LineReadingOutputStream(onLineRead = {line -> out.append(line)}){
            override fun flush() {
                super.flush()
                out.flush()
            }
        }, true))
    }

    private val ColorSupported: Boolean = TerminalColor.COLOR_SUPPORTED

    private fun formatLabel(text: CharSequence): CharSequence {
        return format(text, foreground = Color.Green, format = Format.Bold)
    }

    private fun formatValue(text: CharSequence): CharSequence {
        return format(text, foreground = Color.Blue)
    }

    private fun formatInput(text: CharSequence): CharSequence {
        return format(text, format = Format.Underline)
    }

    fun format(text: CharSequence, foreground: Color? = null, background: Color? = null, format: Format? = null): CharSequence {
        if (!ColorSupported || (foreground == null && background == null && format == null)) return text
        val sb = StringBuilder()
        sb.append("\u001B[")
        if (foreground != null) {
            sb.append(30 + foreground.offset)
            sb.append(';')
        }
        if (background != null) {
            sb.append(40 + background.offset)
            sb.append(';')
        }
        if (format != null) {
            sb.append(format.number)
            sb.append(';')
        }
        sb.setCharAt(sb.length - 1, 'm')
        sb.append(text)
        sb.append("\u001B[0m")
        return sb
    }


    private fun printWarning(text: CharSequence) {
        println(format(text, foreground = Color.Red))
    }

    private fun printWarning(text: CharSequence, input:String, possibilities:Collection<String>) {
        println(format(text, foreground = Color.Red))

        val matchResult = MatchUtils.match(possibilities.toTypedArray(), { it.toLowerCase() }, input.toLowerCase())
        if (matchResult.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("  Did you mean ")
            sb.append(matchResult[0])
            for (i in 1..matchResult.size - 2) {
                sb.append(", ").append(matchResult[i])
            }
            if (matchResult.size >= 2) {
                sb.append(" or ").append(matchResult.last())
            }
            sb.append('?')

            println(format(sb, foreground = Color.Yellow))
        }
    }

    fun evaluateKeyAndPrint(text: String) {
        // TODO Ideally, this would get rewritten with Done message if nothing was written between them
        print(formatLabel("→ "))
        println(formatInput(text))

        val beginTime = System.currentTimeMillis()
        val (key, data, status) = evaluateKey(text)
        val duration = System.currentTimeMillis() - beginTime

        base@when (status) {
            CLI.KeyEvaluationStatus.Success -> {
                print(formatLabel("Done "))
                print(formatInput(text))
                @Suppress("UNCHECKED_CAST")
                val prettyPrinter:((Any?) -> CharSequence)? = key?.prettyPrinter as ((Any?) -> CharSequence)?

                if (prettyPrinter != null) {
                    val printed: CharSequence? =
                        try {
                            prettyPrinter(data)
                        } catch (e:Exception) {
                            null
                        }

                    if (printed != null) {
                        println(formatLabel(": "))
                        println(printed)
                        return@base
                    }
                }

                when (data) {
                    null, is Unit -> println()
                    is Collection<*> -> {
                        print(formatLabel(" ("))
                        print(data.size)
                        println(formatLabel("): "))

                        for (item:Any? in data) {
                            println("  "+ formatValue(PrettyPrinter.toString(item)))
                        }
                    }
                    else -> {
                        print(formatLabel(": "))
                        println(formatValue(PrettyPrinter.toString(data)))
                    }
                }
            }
            CLI.KeyEvaluationStatus.NoProject -> {
                val projectString = data as String?
                if (projectString != null) {
                    printWarning("Can't evaluate $text - no project named '$projectString' found", projectString, AllProjects.keys)
                } else {
                    printWarning("Can't evaluate $text - no project specified")
                }
            }
            CLI.KeyEvaluationStatus.NoConfiguration -> {
                val configString = data as String
                printWarning("Can't evaluate $text - no configuration named '$configString' found", configString, AllConfigurations.keys)
            }
            CLI.KeyEvaluationStatus.NoKey -> {
                val keyString = data as String
                if (keyString == text) {
                    printWarning("Can't evaluate $text - no key or command named '$keyString' found", keyString, AllKeys.keys + commands.keys)
                } else {
                    printWarning("Can't evaluate $text - no key named '$keyString' found", keyString, AllKeys.keys)
                }
            }
            CLI.KeyEvaluationStatus.NotAssigned -> {
                val error = data as WemiException.KeyNotAssignedException
                print(format("Failure: ", Color.Red))
                print(formatInput(error.scope.scopeToString() + error.key.name))
                println(format(" is not set", Color.Red))
            }
            CLI.KeyEvaluationStatus.Exception -> {
                val we = data as WemiException

                val message = we.message
                if (we.showStacktrace || message == null || message.isBlank()) {
                    LOG.error("Error while evaluating $text", we)
                } else {
                    printWarning(message)
                    LOG.debug("Error while evaluating $text", we)
                }
            }
        }

        if (duration > 100) {
            println(format("\tin " + formatTimeDuration(duration), Color.Cyan))
        }
    }

    enum class KeyEvaluationStatus {
        /** Data contains the evaluation result */
        Success,
        /** Data contains invalid name of project or null if tried to use default project but that is undefined */
        NoProject,
        /** Data contains invalid name of configuration */
        NoConfiguration,
        /** Data contains invalid name of key */
        NoKey,
        /** Data contains [WemiException.KeyNotAssignedException] */
        NotAssigned,
        /** Data contains [WemiException] */
        Exception
    }

    data class KeyEvaluationResult(val key:Key<*>?, val data:Any?, val status: KeyEvaluationStatus)

    /**
     * Evaluates given command. Syntax is:
     * `project/key`
     * or
     * `key`
     *
     * (default project will be then used)
     */
    fun evaluateKey(text: String): KeyEvaluationResult {
        var project: Project? = defaultProject
        val configurations = mutableListOf<Configuration>()

        var offset = 0

        // Parse Project
        val projectSlashIndex = text.indexOf('/')
        if (projectSlashIndex != -1) {
            val projectString = text.substring(offset, projectSlashIndex)
            project = AllProjects.findCaseInsensitive(projectString)
            if (project == null) {
                return KeyEvaluationResult(null, projectString, KeyEvaluationStatus.NoProject)
            }
            offset = projectSlashIndex + 1
        } else if (project == null) {
            return KeyEvaluationResult(null,null, KeyEvaluationStatus.NoProject)
        }

        // Parse Configurations
        while (true) {
            val nextConfigEnd = text.indexOf(':', offset)
            if (nextConfigEnd == -1) {
                break
            }

            val configString = text.substring(offset, nextConfigEnd)
            val config = AllConfigurations.findCaseInsensitive(configString) ?: return KeyEvaluationResult(null, configString, KeyEvaluationStatus.NoConfiguration)
            configurations.add(config)
            offset = nextConfigEnd + 1
        }

        // Parse Key
        val keyString = text.substring(offset)
        val key = AllKeys.findCaseInsensitive(keyString) ?: return KeyEvaluationResult(null, keyString, KeyEvaluationStatus.NoKey)

        return try {
            KeyEvaluationResult(key, project.projectScope.run {
                evaluateInNestedScope(key, configurations, 0)
            }, KeyEvaluationStatus.Success)
        } catch (e: WemiException.KeyNotAssignedException) {
            KeyEvaluationResult(key, e, KeyEvaluationStatus.NotAssigned)
        } catch (e: WemiException) {
            KeyEvaluationResult(key, e, KeyEvaluationStatus.Exception)
        }
    }

    // Now this is some mind-bending stuff!
    private fun <Value> Scope.evaluateInNestedScope(key: Key<Value>, all:List<Configuration>, index:Int):Value {
        return if (index == all.size) {
            key.get()
        } else {
            using(all[index]) {
                evaluateInNestedScope(key, all, index + 1)
            }
        }
    }

    private val commands = mapOf(
            "exit" to {
                throw EndOfFileException()
            },
            "projects" to {
                printLabeled("project", AllProjects)
            },
            "configurations" to {
                printLabeled("configuration", AllConfigurations)
            },
            "keys" to {
                printLabeled("key", AllKeys)
            },
            "help" to {
                println(formatLabel("Wemi $WemiVersion (Kotlin $WemiKotlinVersion)"))
                print(formatLabel("Commands: "))
                println("exit, projects, configurations, keys, help")
                print(formatLabel("Keys: "))
                println("Configurations and projects hold values/behavior of keys. That can be mundane data like version of\n" +
                        "the project in 'projectVersion' key or complex operation, like compiling and running in 'run' key.\n" +
                        "To evaluate the key, simply type its name. If you want to run the key in a different project or\n" +
                        "in a configuration, prefix it with its name and slash, for example: desktop/run")
            }
    )

    private fun evaluateCommand(command:String) {
        if (command.isBlank()) {
            return
        }

        val commandFunction = commands[command.toLowerCase()]
        if (commandFunction != null) {
            commandFunction()
        } else {
            try {
                evaluateKeyAndPrint(command)
            } catch (we: WemiException) {

            }
        }
    }

    internal fun beginInteractive() {
        val lineReader = LineReader

        val prompt = format("> ", format = Format.Bold).toString()

        while (true) {
            try {
                val line = lineReader.readLine(prompt)
                if (line.isNullOrBlank()) {
                    continue
                }
                for (word in lineReader.parser.parse(line, line.length).words()) {
                    evaluateCommand(word)
                }
            } catch (interrupt: UserInterruptException) {
                // User wants to delete written line or exit
                if (interrupt.partialLine.isNullOrEmpty()) {
                    break
                }
            } catch (_: EndOfFileException) {
                break
            } catch (_: IOException) {
                break
            }
        }

        System.out.flush()
        System.err.flush()
    }

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

    enum class Format(internal val number: Int) {
        Bold(1), // Label or Prompt
        Italic(3),
        Underline(4), // Input
    }

    private fun printLabeled(label:String, items:Map<String, WithDescriptiveString>) {
        println(formatLabel("${items.size} $label${if (items.isEmpty()) "" else "s"}:"))
        for (value in items.values) {
            print("   ")
            println(value.toDescriptiveAnsiString())
        }
    }

    private val histories = HashMap<String, SimpleHistory>()

    fun getHistory(name:String):History {
        return histories.getOrPut(name) {
            val buildScript = WemiBuildScript
            if (buildScript == null) {
                SimpleHistory(null)
            } else {
                val historiesFolder = buildScript.buildFolder.toPath().resolve("cache/history/")
                Files.createDirectories(historiesFolder)
                val fileName = StringBuilder()
                for (c in name) {
                    if (c.isLetterOrDigit() || c.isWhitespace() || c == '.' || c == '_' || c == '-') {
                        fileName.append(c)
                    } else {
                        fileName.append(c.toInt())
                    }
                }

                SimpleHistory(historiesFolder.resolve(fileName.toString()))
            }
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            for (value in histories.values) {
                value.save()
            }
            HISTORY_LOG.debug("Histories saved")
        }, "HistorySaver"))
    }

    private val NoHistory = SimpleHistory(null)

    private val HISTORY_LOG = LoggerFactory.getLogger(SimpleHistory::class.java)

    private class SimpleHistory(private val path:Path?) : History {

        val DEFAULT_HISTORY_SIZE = 50

        private val items = ArrayList<String>()
        private var index = 0
        private var changed = false

        init {
            load()
        }

        override fun attach(reader: LineReader) {
            // Do nothing, we don't care about what LineReader thinks
        }

        override fun load() {
            if (path == null || !Files.exists(path)) {
                return
            }
            val lines = try {
                Files.readAllLines(path, Charsets.UTF_8)
            } catch (e:Exception) {
                HISTORY_LOG.warn("Failed to load from path {}", path, e)
                return
            }
            clear()
            items.addAll(lines)
            changed = false
            index = size()
        }

        override fun save() {
            if (!changed || path == null) {
                return
            }

            try {
                Files.newBufferedWriter(path, Charsets.UTF_8).use { writer ->
                    for (line in items) {
                        writer.write(line)
                        writer.append('\n')
                    }
                }
            } catch (e:Exception) {
                HISTORY_LOG.warn("Failed to write to path {}", path, e)
                return
            }

            changed = false
        }

        private fun clear() {
            items.clear()
            index = 0
            changed = true
        }

        override fun purge() {
            clear()
            if (path != null) {
                HISTORY_LOG.trace("Purging history from {}", path)
                Files.deleteIfExists(path)
            }
            changed = false
        }

        override fun size(): Int {
            return items.size
        }

        override fun isEmpty(): Boolean {
            return items.isEmpty()
        }

        override fun index(): Int = index

        override fun first(): Int = 0

        override fun last(): Int = items.size - 1

        override fun get(index: Int): String {
            return items[index]
        }

        override fun add(line: String) {
            items.remove(line)
            items.add(line)
            changed = true
            while (size() > DEFAULT_HISTORY_SIZE) {
                items.removeAt(0)
            }
            index = size()
        }

        override fun add(time: Instant, line: String) {
            add(line)
        }

        // This is used when navigating the history
        override fun iterator(index: Int): ListIterator<History.Entry> {
            val iterator = items.listIterator(index)
            return object : ListIterator<History.Entry> {
                override fun hasNext(): Boolean = iterator.hasNext()

                override fun hasPrevious(): Boolean = iterator.hasPrevious()

                override fun nextIndex(): Int = iterator.nextIndex()

                override fun previousIndex(): Int = iterator.previousIndex()

                private fun map(index:Int, line:String):History.Entry {
                    return object : History.Entry {
                        override fun index(): Int = index

                        override fun time(): Instant = Instant.EPOCH

                        override fun line(): String = line
                    }
                }

                override fun next(): History.Entry = map(iterator.nextIndex(), iterator.next())

                override fun previous(): History.Entry = map(iterator.previousIndex(), iterator.previous())
            }
        }

        //
        // Navigation
        //

        /**
         * This moves the history to the last entry. This entry is one position
         * before the moveToEnd() position.
         *
         * @return Returns false if there were no history iterator or the history
         * index was already at the last entry.
         */
        override fun moveToLast(): Boolean {
            val lastEntry = size() - 1
            if (lastEntry >= 0 && lastEntry != index) {
                index = size() - 1
                return true
            }

            return false
        }

        /**
         * Move to the specified index in the history
         */
        override fun moveTo(index: Int): Boolean {
            if (index >= 0 && index < size()) {
                this.index = index
                return true
            }
            return false
        }

        /**
         * Moves the history index to the first entry.
         *
         * @return Return false if there are no iterator in the history or if the
         * history is already at the beginning.
         */
        override fun moveToFirst(): Boolean {
            if (size() > 0 && index != 0) {
                index = 0
                return true
            }
            return false
        }

        /**
         * Move to the end of the history buffer. This will be a blank entry, after
         * all of the other iterator.
         */
        override fun moveToEnd() {
            index = size()
        }

        /**
         * Return the content of the current buffer.
         */
        override fun current(): String {
            return if (index < 0 || index >= size()) {
                ""
            } else {
                items[index]
            }
        }

        /**
         * Move the pointer to the previous element in the buffer.
         *
         * @return true if we successfully went to the previous element
         */
        override fun previous(): Boolean {
            if (index <= 0) {
                return false
            }
            index--
            return true
        }

        /**
         * Move the pointer to the next element in the buffer.
         *
         * @return true if we successfully went to the next element
         */
        override fun next(): Boolean {
            if (index >= size()) {
                return false
            }
            index++
            return true
        }

        override fun toString(): String {
            val sb = StringBuilder()
            for (e in this) {
                sb.append(e.toString()).append("\n")
            }
            return sb.toString()
        }

    }
}
