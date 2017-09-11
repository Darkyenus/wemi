package wemi

import com.darkyen.tproll.util.TerminalColor
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.slf4j.LoggerFactory
import wemi.util.MatchUtils
import wemi.util.WithDescriptiveString
import wemi.util.findCaseInsensitive
import wemi.util.formatTimeDuration
import java.io.File
import java.io.IOException

/**
 *
 */

object CLI {

    private val LOG = LoggerFactory.getLogger(javaClass)

    val Terminal: Terminal = TerminalBuilder.terminal()

    private var defaultProject: Project? = null
        set(value) {
            if (value != null) {
                print(formatLabel("Project: "))
                println(formatValue(value.name))
            }
            field = value
        }

    internal fun init(root: File) {
        val allProjects = AllProjects
        if (allProjects.isEmpty()) {
            printWarning("No project found")
        } else if (allProjects.size == 1) {
            defaultProject = allProjects.values.first()
        } else {
            var closest: Project? = null
            for (project in allProjects.values) {
                if (project.projectRoot == root) {
                    defaultProject = project
                    break
                } else if (closest == null) {
                    closest = project
                } else {
                    // Compare how close they are!
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
            defaultProject = closest
        }
    }

    private val ColorSupported: Boolean = TerminalColor.COLOR_SUPPORTED

    fun formatLabel(text: CharSequence): CharSequence {
        return format(text, foreground = Color.Green, format = Format.Bold)
    }

    fun formatValue(text: CharSequence): CharSequence {
        return format(text, foreground = Color.Blue)
    }

    fun formatInput(text: CharSequence): CharSequence {
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
        val beginTime = System.currentTimeMillis()
        var result: Any? = null
        var error:WemiException.KeyNotAssignedException? = null
        try {
            result = evaluateKey(text)
        } catch (e:WemiException.KeyNotAssignedException) {
            error = e
        }
        val duration = System.currentTimeMillis() - beginTime

        if (error != null) {
            print(format("Failure ", Color.Red))
            print(formatInput(error.scope.scopeToString() + error.key.name))
            println(format(" is not set", Color.Red))
        } else {
            print(formatLabel("Done "))
            print(formatInput(text))
            @Suppress("CascadeIf")
            if (result == null) {
                println()
                // Done
            } else if (result is Collection<*>){
                print(formatLabel(" ("))
                print(result.size)
                println(formatLabel("): "))
                for (item:Any? in result) {
                    println("  "+formatValue(item.toString()))
                }
            } else {
                print(formatLabel(": "))
                println(formatValue(result.toString()))
            }
        }

        if (duration > 100) {
            println(format("\tin " + formatTimeDuration(duration), Color.Cyan))
        }
    }

    /**
     * Evaluates given command. Syntax is:
     * `project/key`
     * or
     * `key`
     *
     * (default project will be then used)
     */
    fun evaluateKey(text: String): Any? {
        var project: Project? = defaultProject
        val configurations = mutableListOf<Configuration>()

        var offset = 0

        // Parse Project
        val projectSlashIndex = text.indexOf('/')
        if (projectSlashIndex != -1) {
            val projectString = text.substring(offset, projectSlashIndex)
            project = AllProjects.findCaseInsensitive(projectString)
            if (project == null) {
                printWarning("Can't evaluate $text - no project named '$projectString' found", projectString, AllProjects.keys)
                return null
            }
            offset = projectSlashIndex + 1
        } else if (project == null) {
            printWarning("Can't evaluate $text - no project specified")
            return null
        }

        // Parse Configurations
        while (true) {
            val nextConfigEnd = text.indexOf(':', offset)
            if (nextConfigEnd == -1) {
                break
            }

            val configString = text.substring(offset, nextConfigEnd)
            val config = AllConfigurations.findCaseInsensitive(configString)
            if (config == null) {
                printWarning("Can't evaluate $text - no configuration named '$configString' found", configString, AllConfigurations.keys)
                return null
            }
            configurations.add(config)
            offset = nextConfigEnd + 1
        }

        // Parse Key
        val keyString = text.substring(offset)
        val key = AllKeys.findCaseInsensitive(keyString)
        if (key == null) {
            printWarning("Can't evaluate $text - no key named '$keyString' found", keyString, AllKeys.keys)
            return null
        }

        return project.run {
            evaluateInNestedScope(key, configurations, 0)
        }
    }

    // Now this is some mind-bending stuff!
    private fun <Value> Scope.evaluateInNestedScope(key:Key<Value>, all:List<Configuration>, index:Int):Value {
        return if (index == all.size) {
            key.get()
        } else {
            with (all[index]) {
                evaluateInNestedScope(key, all, index + 1)
            }
        }
    }

    fun evaluateCommand(command:String) {
        if (command.isBlank()) {
            return
        }

        when (command.toLowerCase()) {
            "exit" ->
                    throw EndOfFileException()
            "projects" -> {
                printLabeled("project", AllProjects)
            }
            "configurations" -> {
                printLabeled("configuration", AllConfigurations)
            }
            "keys" -> {
                printLabeled("key", AllKeys)
            }
            "help" -> {
                println(formatLabel("Wemi $WemiVersion (Kotlin $WemiKotlinVersion)"))
                print(formatLabel("Commands: "))
                println("exit, projects, configurations, keys, help")
                print(formatLabel("Keys: "))
                println("Configurations and projects hold values/behavior of keys. That can be mundane data like version of\n" +
                        "the project in 'projectVersion' key or complex operation, like compiling and running in 'run' key.\n" +
                        "To evaluate the key, simply type its name. If you want to run the key in a different project or\n" +
                        "in a configuration, prefix it with its name and slash, for example: desktop/run")
                return
            }
            else -> {
                try {
                    evaluateKeyAndPrint(command)
                } catch (we:WemiException) {
                    val message = we.message
                    if (we.showStacktrace || message == null || message.isBlank()) {
                        LOG.error("Error while evaluating $command", we)
                    } else {
                        printWarning(message)
                        LOG.debug("Error while evaluating $command", we)
                    }
                }
            }
        }
    }

    internal fun beginInteractive() {
        val lineReader = LineReaderBuilder.builder()
                .appName("Wemi")
                .terminal(Terminal)
                .parser(DefaultParser().apply {
                    isEofOnEscapedNewLine = false
                    isEofOnUnclosedQuote = false
                })
                .build()

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

        printBye()
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
        Bold(1),
        Italic(3),
        Underline(4),
    }

    private fun printLabeled(label:String, items:Map<String, WithDescriptiveString>) {
        println(formatLabel("${items.size} $label${if (items.isEmpty()) "" else "s"}:"))
        for (value in items.values) {
            print("   ")
            println(value.toDescriptiveAnsiString())
        }
    }

    private val Random = java.util.Random()

    private fun <T> Array<T>.random():T {
        return get(Random.nextInt(size))
    }

    private fun printBye() {
        val ByeBodies = arrayOf(
                format("Bye! ", Color.Red),
                format("Bye! ", Color.Cyan),
                format("Bye! ", Color.Green),
                format("Bye! ", Color.Blue),
                format("Bye! ", Color.Yellow),
                format("Bye! ", Color.Magenta),
                "" + format("B", Color.Red) + format("y", Color.Green) + format("e", Color.Yellow) + format("!", Color.Blue) + " ",
                "" + format("B", Color.Green) + format("y", Color.Red) + format("e", Color.White) + format("!", Color.Green) + " ",
                "" + format("B", Color.Cyan) + format("y", Color.Magenta) + format("e", Color.Yellow) + format("!", Color.Black) + " "
        )
        val ByeSuffixes = arrayOf("😀", "😃", "😄", "😊", "🙂", "🙃", "😋", "🤓", "😎", "👽", "😺", "😸", "👋", "👍", "👊", "🤘", "🖖", "🐶", "🐻", "🐼", "🐨", "🐒", "🐸", "🐙", "🐺", "🐲", "🏂", "🏄", "👾", "💎", "🎈", "🎉")

        print(ByeBodies.random())
        println(ByeSuffixes.random())
    }

}
