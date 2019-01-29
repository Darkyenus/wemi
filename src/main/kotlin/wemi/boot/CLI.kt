package wemi.boot

import com.darkyen.tproll.TPLogger
import org.jline.reader.*
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.slf4j.LoggerFactory
import wemi.*
import wemi.util.*
import wemi.util.CliStatusDisplay.Companion.withStatus
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

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

        terminal.handle(org.jline.terminal.Terminal.Signal.INT) {
            // This is what is done by default, but through Shutdown.exit, which does not call shutdown hooks
            System.exit(130)
        }

        terminal
    }

    internal val MessageDisplay: CliStatusDisplay? by lazy {
        if (WemiColorOutputSupported) {
            // If terminal doesn't support color, it probably doesn't support ANSI codes
            CliStatusDisplay(Terminal)
        } else null
    }

    /** Line reader used when awaiting tasks. */
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

    /** Line reader used when reading user input.
     * History should be set separately.*/
    internal val InputLineReader: LineReaderImpl by lazy {
        (LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(DefaultParser().apply {
                    isEofOnEscapedNewLine = false
                    isEofOnUnclosedQuote = false
                })
                .history(SimpleHistory.NoHistory)
                .build() as LineReaderImpl).apply {
            unsetOpt(LineReader.Option.INSERT_TAB)
            unsetOpt(LineReader.Option.MENU_COMPLETE)
        }
    }

    internal fun createReloadBuildScriptLineReader(): LineReaderImpl {
        return (LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(DefaultParser().apply {
                    isEofOnEscapedNewLine = false
                    isEofOnUnclosedQuote = false
                })
                .completer { _, _, candidates ->
                    candidates.add(Candidate("reload", "reload", null, "Reload sources and try compiling again", null, null, false))
                    candidates.add(Candidate("abort", "abort", null, "Abort and exit", null, null, false))
                }
                .history(SimpleHistory.NoHistory)
                .build() as LineReaderImpl).apply {
            unsetOpt(LineReader.Option.INSERT_TAB)
            unsetOpt(LineReader.Option.MENU_COMPLETE)
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

        System.setOut(PrintStream(MessageDisplay, true))
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

    private val KeyEvaluationStatusListener = object : WemiKeyEvaluationListener {

        private val STATUS_PREFIX = if (WemiUnicodeOutputSupported) "• " else "# "
        private val STATUS_INFIX = if (WemiUnicodeOutputSupported) " ‣ " else " > "
        private val STATUS_META_STYLE = AttributedStyle.BOLD.foreground(AttributedStyle.GREEN)
        private val STATUS_CONTENT_STYLE = AttributedStyle.DEFAULT.underline()

        private val messageBuilder = AttributedStringBuilder()
        private val stack = ArrayList<Int>()
        private val importantPrefix:Int

        init {
            messageBuilder.style(STATUS_META_STYLE)
            messageBuilder.append(STATUS_PREFIX)
            importantPrefix = messageBuilder.length
        }

        override fun keyEvaluationStarted(fromScope: Scope, key: Key<*>) {
            stack.add(messageBuilder.length)
            if (stack.size > 1) {
                messageBuilder.style(STATUS_META_STYLE)
                messageBuilder.append(STATUS_INFIX)
            }
            messageBuilder.style(STATUS_CONTENT_STYLE)
            messageBuilder.append(key.name)
            update()
        }

        private fun pop() {
            if (stack.size > 0) {
                messageBuilder.setLength(stack.removeAt(stack.size - 1))
                update()
            }
        }

        private fun update() {
            MessageDisplay?.setMessage(messageBuilder.toAttributedString(), importantPrefix)
        }

        override fun <V> keyEvaluationSucceeded(key: Key<V>, bindingFoundInScope: Scope?, bindingFoundInHolder: BindingHolder?, result: V) {
            pop()
        }

        override fun keyEvaluationFailedByNoBinding(withAlternative: Boolean, alternativeResult: Any?) {
            pop()
        }

        override fun keyEvaluationFailedByError(exception: Throwable, fromKey: Boolean) {
            pop()
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

        val beginTime = System.currentTimeMillis()
        val keyEvaluationResult = useKeyEvaluationListener(KeyEvaluationStatusListener) {
            MessageDisplay.withStatus(true) {
                task.evaluateKey(defaultProject)
            }
        }
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
            val projectName = task.firstInput("project", true)
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

        fun printLabeled(label: String, items: Map<String, WithDescriptiveString>, task:Task) {
            val filter = task.firstInput("filter", true)
            val found:Collection<WithDescriptiveString> = if (filter == null) {
                items.values
            } else {
                items.entries.mapNotNull {(k, v) ->
                    if (k.contains(filter, ignoreCase = true)) {
                        v
                    } else null
                }
            }

            println(formatLabel("${found.size} $label${if (found.isEmpty()) "" else "s"}:"))
            for (value in found) {
                print("   ")
                println(value.toDescriptiveAnsiString())
            }
        }
        put("projects") {
            printLabeled("project", AllProjects, it)
            null
        }
        put("configurations") {
            printLabeled("configuration", AllConfigurations, it)
            null
        }
        put("keys") {
            printLabeled("key", AllKeys, it)
            null
        }

        put("inspect") { task ->
            for ((type, name) in task.input) {
                if (type == "project" || (type.isEmpty() && name.endsWith("/"))) {
                    val projectName = name.removeSuffix("/")
                    val project = AllProjects.findCaseInsensitive(projectName)
                    if (project == null) {
                        println(format("No project named '$projectName' found", Color.White))
                        continue
                    }

                    print(formatLabel("Project Name: "))
                    println(formatValue(project.name))
                    print(formatLabel("  At: "))
                    println(formatValue(project.projectRoot?.toString() ?: "<no root>"))
                } else if (type == "configuration" || (type.isEmpty() && name.endsWith(":"))) {
                    val configurationName = name.removeSuffix(":")
                    val configuration = AllConfigurations.findCaseInsensitive(configurationName)
                    if (configuration == null) {
                        println(format("No configuration named '$configurationName' found", Color.White))
                        continue
                    }

                    print(formatLabel("Configuration Name: "))
                    println(formatValue(configuration.name))
                    println("  \"${format(configuration.description, Color.Black)}\"")
                    if (configuration.parent != null) {
                        print(formatLabel("  Parent: "))
                        println(formatValue(configuration.parent.name))
                    }
                } else if (type == "key" || (type.isEmpty() && name.isNotEmpty() && name.last().isJavaIdentifierPart())) {
                    val key = AllKeys.findCaseInsensitive(name)
                    if (key == null) {
                        println(format("No key named '$name' found", Color.White))
                        continue
                    }

                    print(formatLabel("Key Name: "))
                    println(formatValue(key.name))
                    println("  \"${format(key.description, Color.Black)}\"")
                    if (key.hasDefaultValue) {
                        print(formatLabel("  Default value: "))
                        println(formatValue(key.defaultValue.toString()))
                    }
                    if (key.inputKeys.isNotEmpty()) {
                        println(formatLabel("  Input keys:"))
                        for ((inputKey, description) in key.inputKeys) {
                            println("   "+format(inputKey, format = Format.Bold)+": "+formatValue(description))
                        }
                    }

                    val bindingHolders = LinkedHashSet<BindingHolder>()
                    val modifierHolders = LinkedHashSet<BindingHolder>()

                    fun exploreForHolders(holder:BindingHolder) {
                        if (holder.binding.containsKey(key)) {
                            bindingHolders.add(holder)
                        }
                        if (holder.modifierBindings.containsKey(key)) {
                            modifierHolders.add(holder)
                        }

                        for (extension in holder.configurationExtensions.values) {
                            exploreForHolders(extension)
                        }
                    }

                    for (project in AllProjects.values) {
                        for (holder in project.projectScope.scopeBindingHolders) {
                            exploreForHolders(holder)
                        }
                    }
                    for (configuration in AllConfigurations.values) {
                        exploreForHolders(configuration)
                    }

                    fun showHolders(holder:Set<BindingHolder>) {
                        val typeMap = TreeMap<Class<BindingHolder>, MutableList<BindingHolder>> { a, b ->
                            a.name.compareTo(b.name)
                        }
                        holder.groupByTo(typeMap) { it.javaClass }

                        val sb = StringBuilder()

                        for ((bindingType, bindings) in typeMap) {
                            sb.append("   ").format(Color.Blue).append(bindingType.simpleName)
                                    .format(Color.White).append(" (").append(bindings.size)
                                    .append("):\n").format()

                            for (binding in bindings) {
                                sb.append("    ").append(binding.toDescriptiveAnsiString()).append('\n')
                            }

                            print(sb)
                            sb.setLength(0)
                        }

                    }

                    if (bindingHolders.isEmpty()) {
                        println(format("  No known value bindings", Color.White))
                    } else {
                        println(formatLabel("  Known bindings in:"))
                        showHolders(bindingHolders)
                    }

                    if (modifierHolders.isEmpty()) {
                        println(format("  No known modification bindings", Color.White))
                    } else {
                        println(formatLabel("  Known modification bindings in:"))
                        showHolders(modifierHolders)
                    }
                } else {
                    printWarning("inspect <project/, configuration:, key> - show known info about subject")
                }
            }

            null
        }

        put("trace") { task ->
            var result:TaskEvaluationResult? = null

            val tasks = task.inputs("task")
            if (tasks.isEmpty()) {
                printWarning("trace [values=true/false] <task> - trace task invocation")
            } else {
                val sb = StringBuilder()

                val printValues = BooleanValidator(task.firstInput("values", false) ?: "").use({it}, {true})
                val repeatCount = IntValidator(task.firstInput("times", false) ?: "").use({it}, {1})
                val treePrintingListener = TreeBuildingKeyEvaluationListener(printValues)

                for (cycle in 1 until repeatCount) {
                    for (taskText in tasks) {
                        evaluateLine(taskText)
                    }
                    println("${if(WemiUnicodeOutputSupported) "🐾" else "#"} ${format("Repeat cycle $cycle done", format = Format.Bold)}")
                }

                for (taskText in tasks) {
                    useKeyEvaluationListener(treePrintingListener) {
                        result = evaluateLine(taskText)
                    }
                    treePrintingListener.appendResultTo(sb)

                    println("${if(WemiUnicodeOutputSupported) "🐾" else "#"} ${format("Trace", format = Format.Bold)}")
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

        put("clean") {
            // Deletes all files that start with - or . in build/cache
            if (WemiCacheFolder.isDirectory()) {
                for (cacheEntry in Files.list(WemiCacheFolder)) {
                    val name = cacheEntry.name
                    if (name.startsWith('-') || name.startsWith('.')) {
                        // Delete it
                        LOG.debug("Deleting {}", cacheEntry)
                        cacheEntry.deleteRecursively()
                    }
                }
            }

            for ((_, project) in AllProjects) {
                val cleared = project.projectScope.cleanCache()
                LOG.debug("Cleared {} items from {}", cleared, project)
            }

            null
        }

        put("log") { task ->
            val level = task.firstInput("level", true)
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
            println(formatLabel("Wemi ${Main.WEMI_VERSION} (Kotlin $WemiKotlinVersion, Java ${System.getProperty("java.version")} - ${System.getProperty("java.vendor")})"))
            print(formatLabel("Commands: "))
            println(" exit, reload, help, log <level>")
            println(" projects [filter], configurations [filter], keys [filter] - list available")
            println(" project <project> - change current project")
            println(" trace <task> - run given task and show a hierarchy of used keys")
            println(" inspect <project/, configuration:, key> - show known info about subject")
            println(" clean - clean compile directories and internal cache")
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

    private fun icon(unicode:String, dumb:String, color:Color):CharSequence {
        return format(if (WemiUnicodeOutputSupported) unicode else dumb, color)
    }

    internal val ICON_SUCCESS = icon("✔", "OK", Color.Green)
    internal val ICON_FAILURE = icon("✘", "FAIL", Color.Red)
    internal val ICON_EXCEPTION = icon("❗", "ERROR", Color.Red)
    internal val ICON_UNKNOWN = icon("?", "UNKNOWN", Color.Yellow)
    internal val ICON_SKIPPED = icon("↷", "SKIP", Color.Magenta)
    internal val ICON_SEE_ABOVE = icon("↑", "SEE ABOVE", Color.Magenta)//⤴ seems to be clipped in some contexts
    internal val ICON_ABORTED = icon("■", "ABORT", Color.Yellow)
}
