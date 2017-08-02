package wemi

import com.darkyen.tproll.util.TerminalColor
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.TerminalBuilder
import wemi.util.WithDescriptiveString
import wemi.util.findCaseInsensitive
import wemi.util.formatTimeDuration
import java.io.File
import java.io.IOException

/**
 *
 */

object CLI {

    val Terminal = TerminalBuilder.terminal()

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


    fun printWarning(text: CharSequence) {
        println(format(text, foreground = Color.Yellow, background = Color.Red))
    }

    fun evaluateKeyAndPrint(text: String) {
        val beginTime = System.currentTimeMillis()
        val result = evaluateKey(text)
        val duration = System.currentTimeMillis() - beginTime
        print(formatLabel("Done "))
        if (result == null) {
            println(formatInput(text))
        } else {
            print(formatInput(text))
            print(formatLabel(": "))
            println(formatValue(result.toString()))
        }

        if (duration > 100) {
            print(format("\tin " + formatTimeDuration(duration), Color.Cyan))
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
        val project: Project?
        val key: Key<*>?
        // Parse Project
        val split = text.indexOf('/')
        val keyString: String
        if (split == -1) {
            project = defaultProject
            if (project == null) {
                printWarning("Can't evaluate $text - no project specified")
                return null
            }
            keyString = text
        } else {
            val projectString = text.substring(0, split)
            keyString = text.substring(split + 1)

            project = AllProjects.findCaseInsensitive(projectString)
            if (project == null) {
                printWarning("Can't evaluate $text - no project named '$projectString' found (Existing projects: ${AllProjects.keys.joinToString(", ")})")
                return null
            }
        }
        // Parse Key
        key = AllKeys.findCaseInsensitive(keyString)
        if (key == null) {
            printWarning("Can't evaluate $text - no key named '$keyString' found (Existing keys: ${AllKeys.keys.joinToString(", ")})")
            return null
        }

        return project.run {
            key.getOrNull()
        }
    }

    fun evaluateCommand(command:String) {
        if (command.isNullOrBlank()) {
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
            else ->
                evaluateKeyAndPrint(command)
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
        println(format("Bye! "+ ByeSuffixes.random(), Color.values().random()))
        System.out.flush()
        System.err.flush()
    }

    enum class Color(internal val offset: Int) {
        Black(0),
        Red(1),
        Green(2),
        Yellow(3),
        Blue(4),
        Magenta(5),
        Cyan(6),
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

    private val ByeSuffixes = arrayOf("😀", "😃", "😄", "😊", "🙂", "🙃", "😋", "🤓", "😎", "👽", "😺", "😸", "👋", "👍", "👊", "🤘", "🖖", "🐶", "🐻", "🐼", "🐨", "🐒", "🐸", "🐙", "🐺", "🐲", "🏂", "🏄", "👾", "💎", "🎈", "🎉")
}
