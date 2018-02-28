package wemi.boot

import com.darkyen.tproll.TPLogger
import org.jline.reader.*
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.slf4j.LoggerFactory
import wemi.*
import wemi.util.*
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path

/**
 * Handles user interaction in standard (possibly interactive) mode
 */
object CLI {

    private val LOG = LoggerFactory.getLogger(javaClass)

    /**
     * Terminal used by [CLI]
     */
    private val Terminal: Terminal by lazy {
        val terminal = TerminalBuilder.terminal()

        // Show main thread stack-trace on Ctrl-T
        // NOTE: SIGINFO is supported only on some Unixes, such as OSX
        terminal.handle(org.jline.terminal.Terminal.Signal.INFO) {
            val stackTrace = MainThread.stackTrace
            val state = MainThread.state
            val sb = StringBuilder()
            sb.format(Color.Yellow)
            print(sb)
            println("\nMain thread: $state")
            for (element in stackTrace) {
                print(" at ")
                println(element)
            }
            sb.setLength(0)
            print(sb.format())
            println()
        }

        terminal
    }

    /**
     * Line reader used when awaiting tasks.
     */
    private val TaskLineReader: LineReaderImpl by lazy {
        (LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(TaskParser)
                .completer(TaskCompleter)
                .history(SimpleHistory.getHistory("repl"))
                .build() as LineReaderImpl).apply {
            unsetOpt(LineReader.Option.INSERT_TAB)
            unsetOpt(LineReader.Option.MENU_COMPLETE)
        }
    }

    /**
     * Line reader used when reading user input.
     *
     * History should be set separately.
     */
    internal val InputLineReader: LineReaderImpl by lazy {
        LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(DefaultParser().apply {
                    isEofOnEscapedNewLine = false
                    isEofOnUnclosedQuote = false
                })
                .history(SimpleHistory.NoHistory)
                .build() as LineReaderImpl
    }

    internal fun createReloadBuildScriptLineReader(): LineReaderImpl {
        return LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(DefaultParser().apply {
                    isEofOnEscapedNewLine = false
                    isEofOnUnclosedQuote = false
                })
                .completer { _, _, candidates ->
                    candidates.add(Candidate("r", "reload", null, "Reload sources and try compiling again", null, null, true))
                    candidates.add(Candidate("a", "abort", null, "Abort and exit", null, null, true))
                }
                .history(SimpleHistory.NoHistory)
                .build() as LineReaderImpl
    }

    /**
     * Currently selected default project in interactive CLI
     */
    private var defaultProject: Project? = null
        set(value) {
            if (value != null) {
                print(formatLabel("Project: "))
                println(formatValue(value.name))
            }
            field = value
        }

    /**
     * Find what should be the default project, assuming Wemi is launched from the given [root] path
     */
    internal fun findDefaultProject(root: Path): Project? {
        val allProjects = AllProjects
        when {
            allProjects.isEmpty() -> return null
            allProjects.size == 1 -> return allProjects.values.first()
            else -> {
                var closest: Project? = null
                var closestDist = -1
                projects@for (project in allProjects.values) {
                    when {
                        project.projectRoot == null -> continue@projects
                        project.projectRoot == root -> return project
                        closest == null -> closest = project
                        else -> // Compare how close they are!
                            try {
                                if (closestDist == -1) {
                                    closestDist = root.relativize(closest.projectRoot).nameCount
                                }
                                val projectDist = root.relativize(project.projectRoot).nameCount
                                if (projectDist < closestDist) {
                                    closest = project
                                    closestDist = projectDist
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

    /**
     * Initialize CLI.
     * Needed before any IO operations are done through [CLI].
     */
    internal fun init(root: Path) {
        val shouldBeDefault = findDefaultProject(root)
        if (shouldBeDefault == null) {
            printWarning("No project found")
        } else {
            defaultProject = shouldBeDefault
        }

        val out = Terminal.writer()
        System.setOut(PrintStream(object : LineReadingOutputStream(onLineRead = { line -> out.append(line) }) {
            override fun flush() {
                super.flush()
                out.flush()
            }
        }, true))
    }

    /**
     * Format given text like it is a label.
     */
    private fun formatLabel(text: CharSequence): CharSequence {
        return format(text, foreground = Color.Green, format = Format.Bold)
    }

    /**
     * Format given text like it is a value.
     */
    private fun formatValue(text: CharSequence): CharSequence {
        return format(text, foreground = Color.Blue)
    }

    /**
     * Format given text like it is an echo of user's input.
     */
    private fun formatInput(text: CharSequence): CharSequence {
        return format(text, format = Format.Underline)
    }

    /**
     * Print given text on a line, formatted like warning
     */
    private fun printWarning(text: CharSequence) {
        println(format(text, foreground = Color.Red))
    }

    /**
     * Print given text on a line, formatted like warning.
     * Then attempt to suggest a better input to the user.
     *
     * @param text what went wrong
     * @param input that user entered
     * @param possibilities what user should have entered as input for it to be valid
     */
    private fun printWarning(text: CharSequence, input: String, possibilities: Collection<String>) {
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

    /**
     * Evaluates the key or command and prints human readable, formatted output.
     */
    fun evaluateAndPrint(task: Task): TaskEvaluationResult {
        if (task.couldBeCommand) {
            val commandFunction = commands[task.key]
            if (commandFunction != null) {
                return commandFunction(task)
                        ?: TaskEvaluationResult(null, null, TaskEvaluationStatus.Command)
            }
        }

        // TODO Ideally, this would get rewritten with Done message if nothing was written between them
        print(formatLabel("→ "))
        println(formatInput(task.key))

        val beginTime = System.currentTimeMillis()
        val keyEvaluationResult = task.evaluateKey(defaultProject)
        val (key, data, status) = keyEvaluationResult
        val duration = System.currentTimeMillis() - beginTime

        when (status) {
            TaskEvaluationStatus.Success -> {
                val out = StringBuilder()
                out.format(Color.Green, format = Format.Bold).append("Done ")
                        .format(format = Format.Underline).append(task.key)
                        .format(Color.Green, format = Format.Bold).append(": ").format()

                val newlinePoint = out.length

                @Suppress("UNCHECKED_CAST")
                out.appendKeyResultLn(key as Key<Any?>, data)


                // Add newline at newlinePoint if key result contains newlines (other than the last one)
                if (out.indexOf('\n', newlinePoint) != out.length - 1) {
                    out.insert(newlinePoint, '\n')
                }

                // Print it out
                print(out)
            }
            TaskEvaluationStatus.NoProject -> {
                val projectString = data as String?
                if (projectString != null) {
                    printWarning("Can't evaluate $task - no project named '$projectString' found", projectString, AllProjects.keys)
                } else {
                    printWarning("Can't evaluate $task - no project specified")
                }
            }
            TaskEvaluationStatus.NoConfiguration -> {
                val configString = data as String
                printWarning("Can't evaluate $task - no configuration named '$configString' found", configString, AllConfigurations.keys)
            }
            TaskEvaluationStatus.NoKey -> {
                val keyString = data as String
                if (task.couldBeCommand) {
                    printWarning("Can't evaluate $task - no key or command named '$keyString' found", keyString, AllKeys.keys + commands.keys)
                } else {
                    printWarning("Can't evaluate $task - no key named '$keyString' found", keyString, AllKeys.keys)
                }
            }
            TaskEvaluationStatus.NotAssigned -> {
                val error = data as WemiException.KeyNotAssignedException
                print(format("Failure: ", Color.Red))
                print(formatInput(error.scope.toString() + error.key.name))
                println(format(" is not set", Color.Red))
            }
            TaskEvaluationStatus.Exception -> {
                val we = data as WemiException

                val message = we.message
                if (we.showStacktrace || message == null || message.isBlank()) {
                    LOG.error("Error while evaluating $task", we)
                } else {
                    printWarning(message)
                    LOG.debug("Error while evaluating $task", we)
                }
            }
            TaskEvaluationStatus.Command -> throw IllegalArgumentException() // evaluateKey will not return this
        }

        if (duration > 100) {
            val sb = StringBuilder()
            sb.format(Color.Cyan).append("\tin ").appendTimeDuration(duration).format()
            println(sb)
        }

        return keyEvaluationResult
    }

    /**
     * Known CLI commands.
     */
    private val commands: Map<String, (Task) -> TaskEvaluationResult?> = HashMap<String, (Task) -> TaskEvaluationResult?>().apply {
        put("exit") {
            throw ExitWemi(false)
        }
        put("reload") {
            if (WemiReloadSupported) {
                throw ExitWemi(true)
            } else {
                printWarning("Reload is not supported")
                null
            }
        }
        put("project") { task ->
            val projectName = task.input.find { it.first == null || it.first == "project" }?.second
            if (projectName == null) {
                printWarning("project <project> - switch default project")
            } else {
                val project = AllProjects.findCaseInsensitive(projectName)
                if (project == null) {
                    printWarning("No project named '$projectName' found", projectName, AllProjects.keys)
                } else {
                    defaultProject = project
                }
            }
            null
        }

        fun printLabeled(label: String, items: Map<String, WithDescriptiveString>) {
            println(formatLabel("${items.size} $label${if (items.isEmpty()) "" else "s"}:"))
            for (value in items.values) {
                print("   ")
                println(value.toDescriptiveAnsiString())
            }
        }
        put("projects") {
            printLabeled("project", AllProjects)
            null
        }
        put("configurations") {
            printLabeled("configuration", AllConfigurations)
            null
        }
        put("keys") {
            printLabeled("key", AllKeys)
            null
        }

        put("trace") { task ->
            var result:TaskEvaluationResult? = null

            val tasks = task.input.filter { it.first == null || it.first == "task" }
            if (tasks.isEmpty()) {
                printWarning("trace <task> - trace task invocation")
            } else {
                val sb = StringBuilder()

                val printValues = BooleanValidator(task.input.find { it.first == "values" }?.second ?: "").use({it}, {true})
                val treePrintingListener = TreeBuildingKeyEvaluationListener(printValues)

                for ((_, taskText) in tasks) {
                    useKeyEvaluationListener(treePrintingListener) {
                        result = evaluateLine(taskText)
                    }
                    treePrintingListener.appendResultTo(sb)

                    println("🐾 ${format("Trace", format = Format.Bold)}")
                    if (sb.isEmpty()) {
                        println(format("\t(no keys evaluated)", foreground = Color.White))
                    } else {
                        println(sb)
                    }

                    sb.setLength(0)
                    treePrintingListener.reset()
                }
            }

            result
        }

        put("log") { task ->
            val level = task.input.find { it.first == null || it.first == "level" }?.second
            if (level == null) {
                printWarning("log <level> - change log level")
            } else {
                when (level.toLowerCase()) {
                    "trace", "t" -> TPLogger.TRACE()
                    "debug", "d" -> TPLogger.DEBUG()
                    "info", "i" -> TPLogger.INFO()
                    "warn", "warning", "w" -> TPLogger.WARN()
                    "error", "err", "e" -> TPLogger.ERROR()
                    else -> {
                        printWarning("Unknown log level")
                        print(formatLabel("Possible log levels: "))
                        println("trace, debug, info, warn, error")
                    }
                }
            }
            null
        }
        put("help") {
            println(formatLabel("Wemi $WemiVersion (Kotlin $WemiKotlinVersion)"))
            print(formatLabel("Commands: "))
            println("exit, project <project>, projects, configurations, keys, trace <task>, log <level>, help")
            print(formatLabel("Keys: "))
            println("Configurations and projects hold values/behavior of keys. That can be mundane data like version of\n" +
                    "the project in 'projectVersion' key or complex operation, like compiling and running in 'run' key.\n" +
                    "To evaluate the key, simply type its name. If you want to run the key in a different project or\n" +
                    "in a configuration, prefix it with its name and slash, for example: desktop/run")
            null
        }
    }

    /**
     * Evaluate command or task from the REPL.
     *
     * @return result of last task evaluation, if any
     */
    private fun evaluateLine(command: String):TaskEvaluationResult? {
        if (command.isBlank()) {
            return null
        }

        val parsed = TaskParser.PartitionedLine(listOf(command), true, false)
        val tasks = parsed.tasks

        val errors = parsed.formattedErrors(true)
        if (errors.hasNext()) {
            do {
                println(errors.next())
            } while (errors.hasNext())
            return null
        }

        var lastResult:TaskEvaluationResult? = null
        for (task in tasks) {
            lastResult = evaluateAndPrint(task)
        }
        return lastResult
    }

    /**
     * Begin interactive REPL loop in this thread.
     */
    internal fun beginInteractive() {
        try {
            val lineReader = TaskLineReader

            val prompt = format("> ", format = Format.Bold).toString()

            while (true) {
                val line: String?
                try {
                    line = lineReader.readLine(prompt)
                    if (line.isNullOrBlank()) {
                        continue
                    }
                } catch (interrupt: UserInterruptException) {
                    // User wants to delete written line or exit
                    if (interrupt.partialLine.isNullOrEmpty()) {
                        break
                    }
                    continue
                } catch (_: EndOfFileException) {
                    throw ExitWemi(false)
                } catch (_: IOException) {
                    break
                }

                try {
                    evaluateLine(line)
                } catch (e: ExitWemi) {
                    throw e
                } catch (e: Exception) {
                    LOG.error("Error in interactive loop", e)
                }
            }
        } finally {
            System.out.flush()
            System.err.flush()
        }
    }

    internal val ICON_SUCCESS = format("✔", Color.Green)
    internal val ICON_FAILURE = format("✘", Color.Red)
    internal val ICON_EXCEPTION = format("❗", Color.Red)
    internal val ICON_UNKNOWN = format("?", Color.Yellow)
    internal val ICON_SKIPPED = format("↷", Color.Magenta)
    internal val ICON_SEE_ABOVE = format("↑", Color.Magenta)//⤴ seems to be clipped in some contexts
    internal val ICON_ABORTED = format("■", Color.Yellow)
}
