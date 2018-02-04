package wemi.boot

import com.darkyen.tproll.TPLogger
import com.darkyen.tproll.integration.JavaLoggingIntegration
import com.darkyen.tproll.logfunctions.*
import com.darkyen.tproll.util.PrettyPrinter
import com.darkyen.tproll.util.TimeFormatter
import org.slf4j.LoggerFactory
import wemi.Configurations
import wemi.WemiKotlinVersion
import wemi.WemiVersion
import wemi.WithExitCode
import wemi.util.*
import java.io.*
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths
import java.time.Duration
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Main")

// Standard exit codes
const val EXIT_CODE_SUCCESS = 0
@Suppress("unused")
@Deprecated("Using this exit code is too vague and is thrown by JVM")
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
// Machine-output exit codes
const val EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR = 51
const val EXIT_CODE_MACHINE_OUTPUT_NO_PROJECT_ERROR = 52
const val EXIT_CODE_MACHINE_OUTPUT_NO_CONFIGURATION_ERROR = 53
const val EXIT_CODE_MACHINE_OUTPUT_NO_KEY_ERROR = 54
const val EXIT_CODE_MACHINE_OUTPUT_KEY_NOT_SET_ERROR = 55
const val EXIT_CODE_MACHINE_OUTPUT_INVALID_COMMAND = 56

internal var WemiRunningInInteractiveMode = false
    private set

var WemiBuildScript: BuildScript? = null
    private set

internal val MainThread = Thread.currentThread()

/**
 * Entry point for the WEMI build tool
 */
fun main(args: Array<String>) {
    TPLogger.attachUnhandledExceptionLogger()
    JavaLoggingIntegration.enable()

    var cleanBuild = false

    var errors = 0

    val taskArguments = ArrayList<String>()
    var interactive = false
    var exitIfNoTasks = false
    var machineReadableOutput = false
    var allowBrokenBuildScripts = false
    var root = Paths.get(".").toAbsolutePath()

    var parsingOptions = true

    for (arg in args) {
        if (parsingOptions) {
            if (arg == "--") {
                parsingOptions = false
            } else if (arg.startsWith("-")) {
                val ROOT_PREFIX = "-root="

                // Parse options
                if (arg == "-clean") {
                    cleanBuild = true
                } else if (arg == "-log=trace") {
                    TPLogger.TRACE()
                } else if (arg == "-log=debug" || arg == "-v" || arg == "-verbose") {
                    TPLogger.DEBUG()
                } else if (arg == "-log=info") {
                    TPLogger.INFO()
                } else if (arg == "-log=warn") {
                    TPLogger.WARN()
                } else if (arg == "-log=error") {
                    TPLogger.ERROR()
                } else if (arg.startsWith(ROOT_PREFIX, ignoreCase = true)) {
                    val newRoot = Paths.get(arg.substring(ROOT_PREFIX.length)).toAbsolutePath()
                    if (newRoot.isDirectory()) {
                        root = newRoot
                    } else {
                        errors++
                        if (newRoot.exists()) {
                            println("Can't use $newRoot as root, not a directory")
                        } else {
                            println("Can't use $newRoot as root, not exists")
                        }
                    }
                } else if (arg == "-i" || arg == "-interactive") {
                    interactive = true
                } else if (arg == "-machineReadableOutput") {
                    machineReadableOutput = true
                } else if (arg == "-allowBrokenBuildScripts") {
                    allowBrokenBuildScripts = true
                } else if (arg == "-v" || arg == "-version") {
                    println("Wemi $WemiVersion with Kotlin $WemiKotlinVersion")
                    exitIfNoTasks = true
                } else if (arg == "-?" || arg == "-h" || arg == "-help") {
                    println("Wemi $WemiVersion")
                    println("  -clean")
                    println("      Rebuild build files")
                    println("  -log=<trace|debug|info|warn|error>")
                    println("      Set log level")
                    println("  -i[nteractive]")
                    println("      Force interactive shell even when tasks are specified")
                    println("  -machineReadableOutput")
                    println("      Print out machine readable output, interactivity must be specified explicitly, and allows to take commands from stdin")
                    println("  -allowBrokenBuildScripts")
                    println("      Do not quit on broken build scripts (normally would exit with $EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)")
                    println("  -root=[folder]")
                    println("      Start with [folder] as a root of the project")
                    println("  -v[ersion]")
                    println("      Print version")
                } else {
                    LOG.error("Unknown argument {} (-h for list of arguments)", arg)
                    errors++
                }
            } else {
                taskArguments.add(arg)
                parsingOptions = false
            }
        } else {
            taskArguments.add(arg)
        }
    }

    if (exitIfNoTasks && taskArguments.isEmpty()) {
        exitProcess(EXIT_CODE_SUCCESS)
    }

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


    if (errors > 0) {
        exitProcess(EXIT_CODE_ARGUMENT_ERROR)
    }

    WemiRunningInInteractiveMode = interactive

    LOG.trace("Starting Wemi from root: {}", root)
    val buildFolder = root / "build"
    val buildScriptSources = findBuildScriptSources(buildFolder)

    // Setup logging
    val consoleLogger = ConsoleLogFunction(null, null)
    if (buildScriptSources.isEmpty()) {
        LOG.warn("No build files found")

        TPLogger.setLogFunction(consoleLogger)
    } else {
        LOG.debug("{} build file(s) found", buildScriptSources.size)

        TPLogger.setLogFunction(LogFunctionMultiplexer(
                FileLogFunction(TimeFormatter.AbsoluteTimeFormatter(),
                        LogFileHandler(
                                (buildFolder / "logs").toFile(),
                                DateTimeFileCreationStrategy(
                                        DateTimeFileCreationStrategy.DEFAULT_DATE_TIME_FILE_NAME_FORMATTER,
                                        false,
                                        DateTimeFileCreationStrategy.DEFAULT_LOG_FILE_EXTENSION,
                                        1024L,
                                        Duration.ofDays(60)),
                                false),
                        true),
                consoleLogger
        ))
    }

    val buildScript = run {
        if (buildScriptSources.isEmpty()) {
            if (allowBrokenBuildScripts) {
                null
            } else {
                LOG.warn("No build script sources found in {}", buildFolder)
                exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
            }
        } else {
            directorySynchronized(buildFolder, {
                LOG.info("Waiting for lock on {}", buildFolder)
            }) {
                val compiledBuildScript = getBuildScript(root, buildFolder, buildScriptSources, cleanBuild)
                if (compiledBuildScript == null) {
                    LOG.warn("Failed to prepare build script")
                    if (allowBrokenBuildScripts) {
                        null
                    } else {
                        exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
                    }
                } else {
                    // WemiBuildScript must be initialized before compilation, because compiler may depend on it
                    WemiBuildScript = compiledBuildScript
                    if (compiledBuildScript.ready()) {
                        compiledBuildScript
                    } else {
                        LOG.warn("Build script failed to compile")
                        if (allowBrokenBuildScripts) {
                            null
                        } else {
                            exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
                        }
                    }
                }
            }
        }
    }

    // Load build files now
    if (buildScript != null) {
        PrettyPrinter.setApplicationRootDirectory(buildScript.wemiRoot)

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
    val taskTokens = TaskParser.createTokens(TaskParser.parseTokens(taskArguments))
    val tasks = TaskParser.parseTasks(taskTokens, machineReadableOutput)

    if (machineReadableOutput) {
        taskTokens.machineReadableCheckErrors()

        val out = machineOutput!!
        for (task in tasks) {
            machineReadableEvaluateAndPrint(out, task)
        }

        if (interactive) {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = reader.readLine() ?: break

                val parsedTokens = TaskParser.parseTokens(line, 0)
                val lineTaskTokens = TaskParser.createTokens(parsedTokens.tokens)
                val lineTasks = TaskParser.parseTasks(lineTaskTokens, true)

                lineTaskTokens.machineReadableCheckErrors()

                for (task in lineTasks) {
                    machineReadableEvaluateAndPrint(out, task)
                }
            }
        }
    } else {
        CLI.init(root)

        var lastTaskResult: TaskEvaluationResult? = null

        val formattedErrors = taskTokens.formattedErrors(true)
        if (formattedErrors.hasNext()) {
            println(format("Errors in task input:", Color.Red))
            do {
                println(formattedErrors.next())
            } while (formattedErrors.hasNext())
        } else {
            for (task in tasks) {
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
                    LOG.debug("WithExitCode - using the exit code of '{}': {}", tasks.last(), exitCode)
                } else {
                    LOG.debug("WithExitCode - {} does not provide exit code", tasks.last())
                }
            } else {
                exitCode = EXIT_CODE_TASK_ERROR
                LOG.debug("WithExitCode - {} evaluation failed", tasks.last())
            }
        }
    }

    exitProcess(exitCode)
}

/**
 * Checks if [Tokens] contain errors after parsing.
 * If there are any, print them and exit process.
 */
private fun Tokens<String, TaskParser.TokenType>.machineReadableCheckErrors() {
    val formattedErrors = formattedErrors(false)
    if (formattedErrors.hasNext()) {
        LOG.error("Errors in task input:")
        do {
            LOG.error("{}", formattedErrors.next())
        } while (formattedErrors.hasNext())

        exitProcess(EXIT_CODE_ARGUMENT_ERROR)
    }
}