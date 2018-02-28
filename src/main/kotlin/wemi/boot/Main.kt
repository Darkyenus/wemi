package wemi.boot

import com.darkyen.tproll.TPLogger
import com.darkyen.tproll.integration.JavaLoggingIntegration
import com.darkyen.tproll.logfunctions.*
import com.darkyen.tproll.util.PrettyPrinter
import com.darkyen.tproll.util.TimeFormatter
import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import org.slf4j.LoggerFactory
import wemi.Configurations
import wemi.WemiKotlinVersion
import wemi.WemiVersion
import wemi.WithExitCode
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
    TPLogger.attachUnhandledExceptionLogger()
    JavaLoggingIntegration.enable()

    var cleanBuild = false

    var errors = 0

    val taskArguments = ArrayList<String>()
    var interactive = false
    var exitIfNoTasks = false
    var machineReadableOutput = false
    var allowBrokenBuildScripts = false

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
                        WemiRootFolder = newRoot
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
                } else if (arg == "-supportReload") {
                    WemiReloadSupported = true
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
                    println("  -supportReload")
                    println("      Will enable support for 'reload' command, which exits the process with code $EXIT_CODE_RELOAD to signal that the process should be started again")
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
            .format(Color.Yellow).append('A').format().append("bort?")
            .toString()

    try {
        while (true) {
            when (lineReader.readLine(prompt, null, "r")?.toLowerCase()) {
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

internal class ExitWemi(val reload:Boolean) : Exception(null, null, false, false)