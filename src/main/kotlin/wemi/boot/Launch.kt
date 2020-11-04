@file:JvmName("Launch")
package wemi.boot

import com.darkyen.tproll.TPLogger
import com.darkyen.tproll.integration.JavaLoggingIntegration
import com.darkyen.tproll.logfunctions.DateTimeFileCreationStrategy
import com.darkyen.tproll.logfunctions.FileLogFunction
import com.darkyen.tproll.logfunctions.LogFileHandler
import com.darkyen.tproll.logfunctions.LogFunctionMultiplexer
import com.darkyen.tproll.logfunctions.SimpleLogFunction
import com.darkyen.tproll.util.PrettyPrinter
import com.darkyen.tproll.util.TimeFormatter
import com.darkyen.tproll.util.prettyprint.PrettyPrinterFileModule
import com.darkyen.tproll.util.prettyprint.PrettyPrinterPathModule
import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import org.joda.time.Duration
import org.slf4j.LoggerFactory
import wemi.AllProjects
import wemi.BooleanValidator
import wemi.BuildScriptData
import wemi.Configurations
import wemi.Project
import wemi.WemiException
import wemi.WithExitCode
import wemi.dependency.DependencyExclusion
import wemi.util.Color
import wemi.util.FileSet
import wemi.util.SystemInfo
import wemi.util.WemiPrettyPrintFileModule
import wemi.util.WemiPrettyPrintFunctionModule
import wemi.util.WemiPrettyPrintLocatedPathModule
import wemi.util.WemiPrettyPrintPathModule
import wemi.util.directorySynchronized
import wemi.util.div
import wemi.util.format
import wemi.util.include
import wemi.util.isDirectory
import wemi.util.matchingFiles
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Launch")

// Standard exit codes
const val EXIT_CODE_SUCCESS = 0
const val EXIT_CODE_OTHER_ERROR = 1
const val EXIT_CODE_ARGUMENT_ERROR = 2
const val EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR = 3
/** Exit code to be returned when all conditions for [wemi.WithExitCode] are met,
 * but the key evaluation failed, for any reason, including unset key and evaluation throwing an exception. */
const val EXIT_CODE_TASK_ERROR = 4
/** Exit code reserved for general purpose task failure,
 * for example to be returned by [wemi.WithExitCode.processExitCode]. */
const val EXIT_CODE_TASK_FAILURE = 5
/** When Wemi exits, but wants to be restarted right after that. */
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
    it?.equals("true", true) ?: !SystemInfo.IS_WINDOWS
}

@JvmField
internal val WemiPathTags:Boolean = System.getenv("WEMI_PATH_TAGS").let {
    it?.equals("true", true) ?: System.getenv("TERMINAL_EMULATOR")?.contains("JetBrains", ignoreCase = true) ?: false
}

@JvmField
internal val WemiColorOutputSupported: Boolean = run {
    // Try to determine if color output is enabled from TPROLL_COLOR and WEMI_COLOR variables and set them accordingly
    @Suppress("RedundantIf")// For readability
    val enabled = BooleanValidator(System.getenv("WEMI_COLOR") ?: System.getenv("TPROLL_COLOR") ?: "").value
            ?: // Not specified explicitly, guess
            if (SystemInfo.IS_WINDOWS) {
                // Windows supports color only if terminal is sane
                val term = System.getenv("TERM")
                term?.contains("xterm") == true || term?.contains("color") == true
            } else if (System.getenv("JITPACK")?.equals("true", ignoreCase = true) == true) {
                // When running a JITPACK build, disable colors
                false
            } else {
                // Non-windows machines usually support color
                true
            }
    System.setProperty("tproll.color", enabled.toString())
    enabled
}

/** Directory in which wemi executable is (./) */
lateinit var WemiRootFolder: Path
    private set

/** ./build folder */
lateinit var WemiBuildFolder: Path
    private set

/** ./build/cache folder */
lateinit var WemiCacheFolder: Path
    private set

/** ~/.wemi folder */
lateinit var WemiSystemCacheFolder: Path
    private set

const val WemiBuildScriptProjectName = "wemi-build"

lateinit var WemiBuildScriptProject:Project
    private set

val WemiRuntimeClasspath:List<Path> = ArrayList<Path>().apply {
    val fullClassPath = System.getProperty("java.class.path") ?: ""
    var start = 0
    while (start < fullClassPath.length) {
        var end = fullClassPath.indexOf(File.pathSeparatorChar, start)
        if (end == -1) {
            end = fullClassPath.length
        }

        add(Paths.get(fullClassPath.substring(start, end)))
        start = end + 1
    }
}

internal val MainThread = Thread.currentThread()

/**
 * Exclusions to be used when downloading libraries that will be loaded at runtime into Wemi process itself.
 * This is because Wemi bundles some libraries already and we don't need to duplicate them.
 */
internal val WemiBundledLibrariesExclude = listOf(
        DependencyExclusion("org.jetbrains.kotlin", "kotlin-stdlib"),
        DependencyExclusion("org.jetbrains.kotlin", "kotlin-reflect")
        //TODO Add Wemi itself here!
)

/** Build scripts can place arbitrary tasks to be completed here.
 * Tasks will be evaluated directly after build script is loaded and before pre-specified or interactive tasks.
 * After that, it is not allowed to add new tasks. */
internal var autoRunTasks:ArrayList<Task>? = ArrayList()

/** Entry point for the WEMI build tool. */
fun main(args: Array<String>) {
    var cleanBuild = false
    var interactiveArg:Boolean? = null
    var machineReadableOutput:MachineReadableOutputFormat? = null
    var allowBrokenBuildScripts = false
    var reloadSupported = false
    var skipAutoRun = false
    var rootDirectory:Path? = null

    val options = arrayOf(
            Option('c', "clean", "perform a clean rebuild of build scripts") { _, _ ->
                cleanBuild = true
            },
            Option('l', "log", "set the log level (single letter variants also allowed)", true, "{trace|debug|info|warn|error}") { level, _ ->
                when (level.toLowerCase()) {
                    "trace", "t" -> TPLogger.TRACE()
                    "debug", "d" -> TPLogger.DEBUG()
                    "info", "i" -> TPLogger.INFO()
                    "warning", "warn", "w" -> TPLogger.WARN()
                    "error", "e" -> TPLogger.ERROR()
                    else -> {
                        System.err.println("Unknown log level: $level")
                        exitProcess(EXIT_CODE_ARGUMENT_ERROR)
                    }
                }
            },
            Option('i', "interactive", "enable interactive mode even in presence of tasks") { _, _ ->
                interactiveArg = true
            },
            Option(Option.NO_SHORT_NAME, "non-interactive", "disable interactive mode even when no tasks are present") { _, _ ->
                interactiveArg = false
            },
            Option('v', "verbose", "verbose mode, same as --log=debug") { _, _ ->
                TPLogger.DEBUG()
            },
            Option(Option.NO_SHORT_NAME, "root", "set the root directory of the built project", true, "DIR") { root, _ ->
                val newRoot = Paths.get(root).toAbsolutePath()
                if (Files.isDirectory(newRoot)) {
                    rootDirectory = newRoot
                } else {
                    if (Files.exists(newRoot)) {
                        System.err.println("Can't use $newRoot as root, not a directory")
                    } else {
                        System.err.println("Can't use $newRoot as root, file does not exist")
                    }
                    exitProcess(EXIT_CODE_ARGUMENT_ERROR)
                }
            },
            Option(Option.NO_SHORT_NAME, "machine-readable-output", "create machine readable output, disables implicit interactivity", null, "true|json|shell|false") { arg, _ ->
                when (arg?.toLowerCase() ?: "true") {
                    "true", "json" -> machineReadableOutput = MachineReadableOutputFormat.JSON
                    "shell" -> machineReadableOutput = MachineReadableOutputFormat.SHELL
                    "false" -> machineReadableOutput = null
                    else -> System.err.println("Invalid machine-readable-output parameter: $arg")
                }
            },
            Option(Option.NO_SHORT_NAME, "allow-broken-build-scripts", "ignore build scripts which fail to compile and allow to run without them") { _, _ ->
                allowBrokenBuildScripts = true
            },
            Option(Option.NO_SHORT_NAME, "reload-supported", "signal that launcher will handle reload requests (exit code $EXIT_CODE_RELOAD), enables 'reload' command") { _, _ ->
                reloadSupported = true
            },
            Option(Option.NO_SHORT_NAME, "skip-auto-run", "ignore autoRun() directives in build scripts") { _, _ ->
                skipAutoRun = true
            },
            Option('h', "help", "show this help and exit", false, null) { _, options ->
                Option.printWemiHelp(options)
                exitProcess(EXIT_CODE_SUCCESS)
            },
            Option(Option.NO_SHORT_NAME, "version", "output version information and exit", false, null) { _, _ ->
                System.err.println("Wemi $WemiVersion")
                System.err.println("Copyright 2017–2020 Jan Polák")
                System.err.println("<https://github.com/Darkyenus/WEMI>")
                exitProcess(EXIT_CODE_SUCCESS)
            })

    val taskArguments = Option.parseOptions(args, options)
    if (taskArguments == null) {
        Option.printWemiHelp(options)
        exitProcess(EXIT_CODE_ARGUMENT_ERROR)
    }

    if (rootDirectory == null) {
        rootDirectory = Paths.get(".")
    }

    try {
        rootDirectory = rootDirectory!!.toRealPath(LinkOption.NOFOLLOW_LINKS)
    } catch (e: IOException) {
        System.err.println("Failed to resolve real path of root directory: $rootDirectory")
        e.printStackTrace(System.err)
        exitProcess(EXIT_CODE_OTHER_ERROR)
    }

    val buildDirectory = rootDirectory!!.resolve("build")
    val cacheDirectory = buildDirectory.resolve("cache")

    WemiRootFolder = rootDirectory!!
    WemiBuildFolder = buildDirectory
    WemiCacheFolder = cacheDirectory
    WemiReloadSupported = reloadSupported

    WemiSystemCacheFolder = run {
        val userHomePath = System.getProperty("user.home")
        if (userHomePath.isNullOrBlank()) {
            return@run WemiRootFolder / ".wemi"
        }
        val userHome = Paths.get(userHomePath)
        if (!userHome.isDirectory()) {
            return@run WemiRootFolder / ".wemi"
        }
        userHome / ".wemi"
    }

    TPLogger.attachUnhandledExceptionLogger()
    JavaLoggingIntegration.enable()

    val machineOutput: PrintStream?

    if (machineReadableOutput != null) {
        // Redirect logging to err
        machineOutput = PrintStream(FileOutputStream(FileDescriptor.out))
        System.setOut(System.err)
    } else {
        // Inject pretty printing modules
        PrettyPrinter.PRETTY_PRINT_MODULES.replaceAll { original ->
            when (original) {
                is PrettyPrinterPathModule -> WemiPrettyPrintPathModule()
                is PrettyPrinterFileModule -> WemiPrettyPrintFileModule()
                else -> original
            }
        }
        PrettyPrinter.PRETTY_PRINT_MODULES.add(WemiPrettyPrintLocatedPathModule())
        PrettyPrinter.PRETTY_PRINT_MODULES.add(WemiPrettyPrintFunctionModule())

        machineOutput = null
        if (interactiveArg == null && taskArguments.isEmpty()) {
            interactiveArg = true
        }
    }

    val interactive = interactiveArg ?: false

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
                                    Duration.standardDays(60)),
                            false),
                    true),
            SimpleLogFunction.CONSOLE_LOG_FUNCTION
    ))
    // This logger must be here in a variable, because if it gets GC-ed, the setting will reset. Java logging is that good.
    val jLineLogger = java.util.logging.Logger.getLogger("org.jline")
    jLineLogger.level = java.util.logging.Level.OFF

    val buildScriptInfo = prepareBuildScriptInfo(allowBrokenBuildScripts, interactive && machineReadableOutput == null, cleanBuild)

    if (buildScriptInfo == null || buildScriptInfo.hasErrors) {
        if (!allowBrokenBuildScripts) {
            // Failure to prepare build script (already logged)
            exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
        }

        LOG.debug("Blank build file loaded")
    }
    val buildScriptProject = createProjectFromBuildScriptInfo(buildScriptInfo)

    // Load build script configuration
    WemiBuildScriptProject = buildScriptProject
    BuildScriptData.AllProjects[buildScriptProject.name] = buildScriptProject

    val errors = buildScriptInfo?.let { loadBuildScript(it) } ?: 0
    if (errors > 0 && !allowBrokenBuildScripts) {
        exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
    }

    // Process initializers
    BuildScriptData.flushInitializers()

    // - Ensure Configurations are loaded -
    Configurations
    // ------------------------------------

    // Auto-run
    val autoRunTasks = autoRunTasks!!
    wemi.boot.autoRunTasks = null
    if (skipAutoRun) {
        if (autoRunTasks.isNotEmpty()) {
            LOG.info("Skipping {} auto run task(s)", autoRunTasks.size)
        }
    } else {
        for (task in autoRunTasks) {
            LOG.info("Auto-run: {}", task)
            val result = task.evaluateKey(null, null)
            when (result.status) {
                TaskEvaluationStatus.Success -> {
                    LOG.debug("Success")
                }
                TaskEvaluationStatus.NoProject -> {
                    LOG.warn("Failure: invalid or missing project")
                }
                TaskEvaluationStatus.NoConfiguration -> {
                    LOG.warn("Failure: invalid configuration")
                }
                TaskEvaluationStatus.NoKey -> {
                    LOG.warn("Failure: invalid key")
                }
                TaskEvaluationStatus.NotAssigned -> {
                    LOG.warn("Failure: key has no bound value")
                }
                TaskEvaluationStatus.Exception -> {
                    LOG.warn("Failure: failed with exception", result.data)
                }
                TaskEvaluationStatus.Command -> {
                    LOG.debug("Success (command)")
                }
            }
        }
    }

    var exitCode = EXIT_CODE_SUCCESS
    val parsedArgs = TaskParser.PartitionedLine(taskArguments, false, machineReadableOutput != null)

    if (machineReadableOutput != null) {
        parsedArgs.machineReadableCheckErrors()

        val out = machineOutput!!
        for (task in parsedArgs.tasks) {
            machineReadableEvaluateAndPrint(out, task, machineReadableOutput!!)
        }

        if (interactive) {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = reader.readLine() ?: break

                val parsed = TaskParser.PartitionedLine(arrayOf(line), allowQuotes = true, machineReadable = true)
                parsed.machineReadableCheckErrors()

                for (task in parsed.tasks) {
                    machineReadableEvaluateAndPrint(out, task, machineReadableOutput!!)
                }
            }
        }
    } else {
        CLI.init(WemiRootFolder)

        try {
            val formattedErrors = parsedArgs.formattedErrors(true)
            if (formattedErrors.hasNext()) {
                println(format("Errors in task input:", Color.Red))
                do {
                    println(formattedErrors.next())
                } while (formattedErrors.hasNext())
            } else {
                for (task in parsedArgs.tasks) {
                    val lastTaskResult = CLI.evaluateAndPrint(task)
                    if (interactive) {
                        continue
                    }

                    when (lastTaskResult.status) {
                        TaskEvaluationStatus.Success, TaskEvaluationStatus.Command -> {
                            val data = lastTaskResult.data
                            if (data is WithExitCode) {
                                exitCode = data.processExitCode()
                                LOG.debug("WithExitCode - using the exit code of '{}': {}", parsedArgs.tasks.last(), exitCode)
                            } else {
                                LOG.debug("WithExitCode - {} does not provide exit code", parsedArgs.tasks.last())
                            }
                        }
                        else -> {
                            exitCode = EXIT_CODE_TASK_ERROR
                            LOG.debug("WithExitCode - {} evaluation failed", parsedArgs.tasks.last())
                        }
                    }
                    if (exitCode != EXIT_CODE_SUCCESS) {
                        break
                    }
                }
            }

            if (interactive) {
                CLI.beginInteractive()
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

private fun prepareBuildScriptInfo(allowBrokenBuildScripts:Boolean, askUser:Boolean, cleanBuild:Boolean):BuildScriptInfo? {
    var finalBuildScriptInfo:BuildScriptInfo? = null

    val buildScriptSourceSet = FileSet(WemiBuildFolder, include("*.kt"))

    do {
        var keepTrying = false

        val buildScriptSources = buildScriptSourceSet.matchingFiles()
        val noSources = if (buildScriptSources.isEmpty()) {
            if (allowBrokenBuildScripts) {
                LOG.warn("No build script sources found in {}", WemiBuildFolder)
                true
            } else if (!askUser) {
                LOG.error("No build script sources found in {}", WemiBuildFolder)
                break
            } else {
                keepTrying = buildScriptsBadAskIfReload("No build script sources found")
                continue
            }
        } else false

        finalBuildScriptInfo = directorySynchronized(WemiBuildFolder, {
            LOG.info("Waiting for lock on {}", WemiBuildFolder)
        }) {
            val buildScriptInfo = getBuildScript(WemiCacheFolder, buildScriptSourceSet, buildScriptSources, cleanBuild)

            if (buildScriptInfo == null || buildScriptInfo.hasErrors) {
                if (allowBrokenBuildScripts) {
                    LOG.warn("Failed to prepare build script")
                    if (buildScriptInfo == null) {
                        return@directorySynchronized null
                    }
                } else if (!askUser) {
                    LOG.error("Failed to prepare build script")
                    return@directorySynchronized null
                } else {
                    keepTrying = buildScriptsBadAskIfReload("Failed to prepare build script")
                    return@directorySynchronized null
                }
            }
            LOG.debug("Obtained build script info {}", buildScriptInfo)

            if (noSources) {
                buildScriptInfo.hasErrors = true
                LOG.info("Build script compilation skipped - no sources")
                return@directorySynchronized buildScriptInfo
            }

            try {
                compileBuildScript(buildScriptInfo)
                return@directorySynchronized buildScriptInfo
            } catch (ce: WemiException) {
                val message = ce.message
                if (ce.showStacktrace || message == null || message.isBlank()) {
                    LOG.error("Build script failed to compile", ce)
                } else {
                    LOG.error("Build script failed to compile: {}", message)
                    LOG.debug("Build script failed to compile due to", ce)
                }

                if (allowBrokenBuildScripts) {
                    buildScriptInfo.hasErrors = true
                    return@directorySynchronized buildScriptInfo
                }

                if (!askUser) {
                    LOG.error("Build script failed to compile")
                    keepTrying = false
                } else {
                    keepTrying = buildScriptsBadAskIfReload("Build script failed to compile")
                }

                return@directorySynchronized null
            }
        }
    } while (keepTrying)

    return finalBuildScriptInfo
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



/**
 * Find what should be the default project, assuming Wemi is launched from the given [root] path
 */
internal fun findDefaultProject(root: Path): Project? {
    val absRoot = root.toAbsolutePath().normalize()
    val allProjects = AllProjects
    var closest: Project? = null
    var closestDist = Int.MAX_VALUE
    projects@for (project in allProjects.values) {
        val absProject = project.projectRoot?.toAbsolutePath()?.normalize()
        when {
            project === WemiBuildScriptProject ||
                    absProject == null -> continue@projects
            absProject == absRoot -> return project
            else -> {// Compare how close they are!
                // IllegalArgumentException may happen if projects are on different filesystems
                val projectDist = try { absRoot.relativize(absProject).nameCount } catch (_:IllegalArgumentException) { 10000 + absRoot.nameCount }
                if (projectDist < closestDist) {
                    closest = project
                    closestDist = projectDist
                }
            }
        }
    }
    LOG.trace("findDefaultProject({}) = {}", root, closest)
    return closest
}

internal class ExitWemi(val reload:Boolean) : Exception(null, null, false, false)