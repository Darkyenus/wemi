package wemi.boot

import com.darkyen.tproll.TPLogger
import com.darkyen.tproll.integration.JavaLoggingIntegration
import com.darkyen.tproll.logfunctions.*
import com.darkyen.tproll.util.PrettyPrinter
import com.darkyen.tproll.util.TimeFormatter
import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import org.jline.utils.OSUtils
import org.slf4j.LoggerFactory
import wemi.*
import wemi.dependency.DefaultExclusions
import wemi.dependency.DependencyExclusion
import wemi.util.*
import java.io.*
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Main")

// Standard exit codes
const val EXIT_CODE_SUCCESS = 0
@Suppress("unused")
@Deprecated("Using this exit code is too vague")
const val EXIT_CODE_UNKNOWN_ERROR = 1
const val EXIT_CODE_ARGUMENT_ERROR = 2
const val EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR = 3
/**
 * Exit code to be returned when all conditions for [wemi.WithExitCode] are met,
 * but the key evaluation failed, for any reason, including unset key and evaluation throwing an exception.
 */
const val EXIT_CODE_TASK_ERROR = 4
/** Exit code reserved for general purpose task failure,
 * for example to be returned by [wemi.WithExitCode.processExitCode]. */
const val EXIT_CODE_TASK_FAILURE = 5
/**
 * When Wemi exits, but wants to be restarted right after that.
 */
const val EXIT_CODE_RELOAD = 6
// Machine-output exit codes
const val EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR = 51
const val EXIT_CODE_MACHINE_OUTPUT_NO_PROJECT_ERROR = 52
const val EXIT_CODE_MACHINE_OUTPUT_NO_CONFIGURATION_ERROR = 53
const val EXIT_CODE_MACHINE_OUTPUT_NO_KEY_ERROR = 54
const val EXIT_CODE_MACHINE_OUTPUT_KEY_NOT_SET_ERROR = 55
const val EXIT_CODE_MACHINE_OUTPUT_INVALID_COMMAND = 56

internal var WemiRunningInInteractiveMode = false
    private set

internal var WemiReloadSupported = false
    private set

@JvmField
internal val WemiUnicodeOutputSupported:Boolean = System.getenv("WEMI_UNICODE").let {
    if (it == null)!OSUtils.IS_WINDOWS else it == "true" }

@JvmField
internal val WemiColorOutputSupported:Boolean = run {
    // Try to determine if color output is enabled from TPROLL_COLOR and WEMI_COLOR variables and set them accordingly
    val env = System.getenv("WEMI_COLOR") ?: System.getenv("TPROLL_COLOR") ?: ""

    val value = BooleanValidator(env).value ?: run {
        if (OSUtils.IS_WINDOWS) {
            // Windows supports color only if terminal is sane
            val term = System.getenv("TERM")
            term?.contains("xterm") == true || term?.contains("color") == true
        } else {
            // Non-windows machines usually support color
            true
        }
    }
    System.setProperty("tproll.color", value.toString())
    value
}

/**
 * Directory in which wemi executable is (./)
 */
var WemiRootFolder: Path = Paths.get(".").toRealPath(LinkOption.NOFOLLOW_LINKS)
    private set(value) {
        if (field == value) {
            return
        }
        field = value.toRealPath(LinkOption.NOFOLLOW_LINKS)
        WemiBuildFolder = field / "build"
    }

/**
 * ./build folder
 */
var WemiBuildFolder: Path = WemiRootFolder / "build"
    private set(value) {
        if (field == value) {
            return
        }
        field = value
        WemiCacheFolder = value / "cache"
    }

/**
 * ./build/cache folder
 */
var WemiCacheFolder: Path = WemiBuildFolder / "cache"
    private set

var WemiBuildScript: BuildScript? = null
    private set

internal val MainThread = Thread.currentThread()

/**
 * Exclusions to be used when downloading libraries that will be loaded at runtime into Wemi process itself.
 * This is because Wemi bundles some libraries already and we don't need to duplicate them.
 */
internal val WemiBundledLibrariesExclude = DefaultExclusions + listOf(
        DependencyExclusion("org.jetbrains.kotlin", "kotlin-stdlib", "*"),
        DependencyExclusion("org.jetbrains.kotlin", "kotlin-reflect", "*"))

/**
 * Entry point for the WEMI build tool
 */
fun main(args: Array<String>) {
    var cleanBuild = false
    var interactive = false
    var machineReadableOutput = false
    var allowBrokenBuildScripts = false

    var options:Array<Option> = emptyArray() // To be accessible inside its declaration
    options = arrayOf(
            Option('c', "clean", "perform a clean rebuild of build scripts",
                    false, null) {
                cleanBuild = true
            },
            Option('l', "log", "set the log level to one of: trace, debug, info, warn, error",
                    true, "LEVEL") {
                when (it) {
                    "trace", "t" -> TPLogger.TRACE()
                    "debug", "d" -> TPLogger.DEBUG()
                    "info", "i" -> TPLogger.INFO()
                    "warn", "w" -> TPLogger.WARN()
                    "error", "e" -> TPLogger.ERROR()
                    else -> {
                        System.err.println("Unknown log level: $it")
                        printWemiHelp(options, EXIT_CODE_ARGUMENT_ERROR)
                    }
                }
            },
            Option('i', "interactive", "enable interactive mode even in presence of flags",
                    false, null) {
                interactive = true
            },
            Option('v', "verbose", "verbose mode, same as --log=debug",
                    false, null) {
                TPLogger.DEBUG()
            },
            Option(null, "root", "set the root directory of the built project",
                    true, "DIR") { root ->
                val newRoot = Paths.get(root).toAbsolutePath()
                if (newRoot.isDirectory()) {
                    WemiRootFolder = newRoot
                } else {
                    if (newRoot.exists()) {
                        println("Can't use $newRoot as root, not a directory")
                    } else {
                        println("Can't use $newRoot as root, not exists")
                    }
                    exitProcess(EXIT_CODE_ARGUMENT_ERROR)
                }
            },
            Option(null, "machine-readable-output", "create machine readable output, disables implicit interactivity",
                    false, null) {
                machineReadableOutput = true
            },
            Option(null, "ignore-broken-build-scripts", "ignore build scripts which fail to compile",
                    false, null) {
                allowBrokenBuildScripts = true
            },
            Option(null, "reload-supported", "signal that launcher will handle reload requests (exit code $EXIT_CODE_RELOAD), enables 'reload' command",
                    false, null) {
                WemiReloadSupported = true
            },
            Option('h', "help", "show this help and exit", false, null) {
                printWemiHelp(options, EXIT_CODE_SUCCESS)
            },
            Option(null, "version", "output version information and exit", false, null) {
                System.err.println("wemi $WemiVersion (Kotlin $WemiKotlinVersion)")
                System.err.println("Copyright (C) 2018 Jan Polák")
                System.err.println("<https://github.com/Darkyenus/WEMI>")
                exitProcess(EXIT_CODE_SUCCESS)
            }
    )

    val taskArguments = parseOptions(args, options)

    TPLogger.attachUnhandledExceptionLogger()
    JavaLoggingIntegration.enable()

    val machineOutput: PrintStream?

    if (machineReadableOutput) {
        // Redirect logging to err
        machineOutput = PrintStream(FileOutputStream(FileDescriptor.out))
        System.setOut(System.err)
    } else {
        machineOutput = null

        if (taskArguments.isEmpty()) {
            interactive = true
        }
    }

    WemiRunningInInteractiveMode = interactive
    LOG.trace("Starting Wemi from root: {}", WemiRootFolder)

    // Setup logging
    TPLogger.setLogFunction(LogFunctionMultiplexer(
            FileLogFunction(TimeFormatter.AbsoluteTimeFormatter(),
                    LogFileHandler(
                            (WemiBuildFolder / "logs").toFile(),
                            DateTimeFileCreationStrategy(
                                    DateTimeFileCreationStrategy.DEFAULT_DATE_TIME_FILE_NAME_FORMATTER,
                                    false,
                                    DateTimeFileCreationStrategy.DEFAULT_LOG_FILE_EXTENSION,
                                    1024L,
                                    Duration.ofDays(60)),
                            false),
                    true),
            ConsoleLogFunction(null, null)
    ))

    val buildScript = prepareBuildScript(allowBrokenBuildScripts, interactive && !machineReadableOutput, cleanBuild)

    // Load build files now
    if (buildScript != null) {
        PrettyPrinter.setApplicationRootDirectory(WemiRootFolder)

        val urls = arrayOfNulls<URL>(1 + buildScript.classpath.size)
        urls[0] = buildScript.scriptJar.toUri().toURL()
        var i = 1
        for (file in buildScript.classpath) {
            urls[i++] = file.toUri().toURL()
        }
        val loader = URLClassLoader(urls, WemiDefaultClassLoader)
        LOG.debug("Loading build file {}", buildScript)
        for (initClass in buildScript.initClasses) {
            try {
                Class.forName(initClass, true, loader)
            } catch (e: Exception) {
                LOG.warn("Failed to load build file class {} from {}", initClass, urls, e)
            }
        }
        LOG.debug("Build file loaded")
    }

    // - Ensure Configurations are loaded -
    Configurations
    // ------------------------------------

    var exitCode = EXIT_CODE_SUCCESS
    val parsedArgs = TaskParser.PartitionedLine(taskArguments, false, machineReadableOutput)

    if (machineReadableOutput) {
        parsedArgs.machineReadableCheckErrors()

        val out = machineOutput!!
        for (task in parsedArgs.tasks) {
            machineReadableEvaluateAndPrint(out, task)
        }

        if (interactive) {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = reader.readLine() ?: break

                val parsed = TaskParser.PartitionedLine(listOf(line), true, true)
                parsed.machineReadableCheckErrors()

                for (task in parsed.tasks) {
                    machineReadableEvaluateAndPrint(out, task)
                }
            }
        }
    } else {
        CLI.init(WemiRootFolder)

        try {
            var lastTaskResult: TaskEvaluationResult? = null

            val formattedErrors = parsedArgs.formattedErrors(true)
            if (formattedErrors.hasNext()) {
                println(format("Errors in task input:", Color.Red))
                do {
                    println(formattedErrors.next())
                } while (formattedErrors.hasNext())
            } else {
                for (task in parsedArgs.tasks) {
                    lastTaskResult = CLI.evaluateAndPrint(task)
                }
            }

            if (interactive) {
                CLI.beginInteractive()
            } else if (lastTaskResult != null) {
                if (lastTaskResult.status == TaskEvaluationStatus.Success) {
                    val data = lastTaskResult.data
                    if (data is WithExitCode) {
                        exitCode = data.processExitCode()
                        LOG.debug("WithExitCode - using the exit code of '{}': {}", parsedArgs.tasks.last(), exitCode)
                    } else {
                        LOG.debug("WithExitCode - {} does not provide exit code", parsedArgs.tasks.last())
                    }
                } else {
                    exitCode = EXIT_CODE_TASK_ERROR
                    LOG.debug("WithExitCode - {} evaluation failed", parsedArgs.tasks.last())
                }
            }
        } catch (exit:ExitWemi) {
            if (exit.reload) {
                exitCode = EXIT_CODE_RELOAD
            }
        }
    }

    exitProcess(exitCode)
}

/**
 * Checks if [TaskParser.PartitionedLine] contain errors after parsing.
 * If there are any, print them and exit process.
 */
private fun TaskParser.PartitionedLine.machineReadableCheckErrors() {
    val formattedErrors = formattedErrors(false)
    if (formattedErrors.hasNext()) {
        LOG.error("Errors in task input:")
        do {
            LOG.error("{}", formattedErrors.next())
        } while (formattedErrors.hasNext())

        exitProcess(EXIT_CODE_ARGUMENT_ERROR)
    }
}

private fun prepareBuildScript(allowBrokenBuildScripts:Boolean, askUser:Boolean, cleanBuild:Boolean):BuildScript? {
    var buildScript:BuildScript? = null

    var Break = false
    do {
        val buildScriptSources = findBuildScriptSources(WemiBuildFolder)
        if (buildScriptSources.isEmpty()) {
            if (allowBrokenBuildScripts) {
                LOG.warn("No build script sources found in {}", WemiBuildFolder)
                break
            } else if (!askUser) {
                LOG.error("No build script sources found in {}", WemiBuildFolder)
                exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
            } else {
                if (buildScriptsBadAskIfReload("No build script sources found")) {
                    continue
                } else {
                    exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
                }
            }
        }

        Break = directorySynchronized(WemiBuildFolder, {
            LOG.info("Waiting for lock on {}", WemiBuildFolder)
        }) {
            val compiledBuildScript = getBuildScript(WemiCacheFolder, buildScriptSources, cleanBuild)

            if (compiledBuildScript == null) {
                if (allowBrokenBuildScripts) {
                    LOG.warn("Failed to prepare build script")
                    true //break
                } else if (!askUser) {
                    LOG.error("Failed to prepare build script")
                    exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
                } else {
                    if (buildScriptsBadAskIfReload("Failed to prepare build script")) {
                        false //continue
                    } else {
                        exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
                    }
                }
            } else {
                // WemiBuildScript must be initialized before compilation, because compiler may depend on it
                WemiBuildScript = compiledBuildScript
                if (compiledBuildScript.ready()) {
                    buildScript = compiledBuildScript
                    true //break
                } else if (allowBrokenBuildScripts) {
                    LOG.warn("Build script failed to compile")
                    true //break
                } else if (!askUser) {
                    LOG.error("Build script failed to compile")
                    exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
                } else {
                    if (buildScriptsBadAskIfReload("Build script failed to compile")) {
                        false //continue
                    } else {
                        exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
                    }
                }
            }
        }
    } while (!Break && buildScript == null)

    return buildScript
}

private fun buildScriptsBadAskIfReload(problem:String):Boolean {
    val lineReader = CLI.createReloadBuildScriptLineReader()

    val prompt = StringBuilder()
            .format(Color.Red).append(problem).format().append(": ")
            .format(Color.Yellow).append('R').format().append("eload/")
            .format(Color.Yellow).append('A').format().append("bort?\n> ")
            .toString()

    try {
        while (true) {
            when (lineReader.readLine(prompt, null, "r")?.trim()?.toLowerCase()) {
                "reload", "r" ->
                    return true
                "abort", "a" ->
                    return false
            }
        }
    } catch (e: UserInterruptException) {
        return false
    } catch (e: EndOfFileException) {
        return false
    }
}

private class Option(
        /** Short option name, if any */
        val short:Char?,
        /** Long option name, if any */
        val long:String?,
        /** Description of the option, used for printing help */
        val description:String,
        /** Whether or not this option takes an argument.
         * true -> argument must be present
         * false -> argument must not be present
         * null -> argument must be present for short, may be present for long */
        val argument:Boolean?,
        /** Single word description of the argument's value. Null if [argument] = false. */
        val argumentDescription:String?,
        /** Function to call when this option is encountered, with */
        val handle:(String?)->Unit)

/** Print option help and exit. */
private fun printWemiHelp(options:Array<Option>, exitCode:Int):Nothing {
    // https://www.gnu.org/prep/standards/html_node/_002d_002dhelp.html
    System.err.println("Usage: wemi [OPTION]... [TASK]...")
    System.err.println("Wemi build system")
    val lines = Array(options.size) { StringBuilder(120) }

    // Add options
    var maxLineLength = 0
    for ((i, option) in options.withIndex()) {
        val line = lines[i]
        if (option.short == null) {
            line.appendTimes(' ', 5)
        } else {
            line.append("  -").append(option.short)
            if (option.long == null) {
                line.append(' ')
            } else {
                line.append(',')
            }
        }

        if (option.long != null) {
            line.append(" --").append(option.long)
            if (option.argument != false) {
                line.append('=').append(option.argumentDescription ?: "ARG")
            }
        }

        if (line.length > maxLineLength) {
            maxLineLength = line.length
        }
    }

    // Add descriptions and print
    maxLineLength += 2 // Separate from descriptions
    for ((i, option) in options.withIndex()) {
        val line = lines[i]
        line.appendTimes(' ', maxLineLength - line.length)
        line.append(option.description)
        System.err.println(line)
    }

    System.err.println("Wemi on Github: <https://github.com/Darkyenus/WEMI>")

    exitProcess(exitCode)
}

/**
 * Parse given command line options, handling them as they are encountered.
 * @return remaining, non-option arguments or exits if something went wrong
 */
private fun parseOptions(args:Array<String>, options:Array<Option>):List<String> {
    // https://www.gnu.org/software/libc/manual/html_node/Argument-Syntax.html
    var argsIndex = 0
    while (argsIndex < args.size) {
        val arg = args[argsIndex++]
        if (arg == "--") {
            // End of options
            break
        } else if (arg.startsWith("--")) {
            // Long option
            val equalsIndex = arg.indexOf('=')
            val optName = if (equalsIndex >= 0) arg.substring(2, equalsIndex) else arg.substring(2)
            val option = options.find { option: Option ->  option.long == optName }
            if (option == null) {
                System.err.println("Unknown option: --$optName")
                printWemiHelp(options, EXIT_CODE_ARGUMENT_ERROR)
            }

            if (option.argument == false && equalsIndex >= 0) {
                System.err.println("--$optName does not take arguments")
                printWemiHelp(options, EXIT_CODE_ARGUMENT_ERROR)
            }

            if (option.argument == true && equalsIndex < 0) {
                System.err.println("--$optName needs an argument")
                printWemiHelp(options, EXIT_CODE_ARGUMENT_ERROR)
            }

            val argument = if (equalsIndex >= 0) arg.substring(equalsIndex+1) else null

            option.handle(argument)
        } else if (arg.startsWith("-") && arg.length > 1) {
            // Short options
            var shortOptIndex = 1
            while (shortOptIndex < arg.length) {
                val optName = arg[shortOptIndex++]
                val option = options.find { option:Option -> option.short == optName }
                if (option == null) {
                    System.err.println("Unknown option: -$optName")
                    printWemiHelp(options, EXIT_CODE_ARGUMENT_ERROR)
                }

                val argument:String?

                if (option.argument != false) {
                    shortOptIndex = arg.length // No more options in this arg
                    if (shortOptIndex + 1 < arg.length) {
                        // Argument is without blank
                        argument = arg.substring(shortOptIndex)
                    } else if (argsIndex < args.size) {
                        // Argument is in the next args
                        argument = args[argsIndex++]
                    } else {
                        System.err.println("-$optName needs an argument")
                        printWemiHelp(options, EXIT_CODE_ARGUMENT_ERROR)
                    }
                } else {
                    argument = null
                }

                option.handle(argument)
            }
        } else {
            // Not part of options
            argsIndex--
            break
        }
    }

    return args.takeLast(args.size - argsIndex)
}

internal class ExitWemi(val reload:Boolean) : Exception(null, null, false, false)