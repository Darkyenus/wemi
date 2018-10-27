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
import wemi.boot.Main.*
import wemi.dependency.DefaultExclusions
import wemi.dependency.DependencyExclusion
import wemi.util.Color
import wemi.util.directorySynchronized
import wemi.util.div
import wemi.util.format
import java.io.*
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Main")

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

/** Directory in which wemi executable is (./) */
lateinit var WemiRootFolder: Path
    private set

/** ./build folder */
lateinit var WemiBuildFolder: Path
    private set

/** ./build/cache folder */
lateinit var WemiCacheFolder: Path
    private set

const val WemiBuildScriptProjectName = "wemi-build"

lateinit var WemiBuildScriptProject:Project
    private set

lateinit var WemiRuntimeClasspath:List<Path>
    private set

internal val MainThread = Thread.currentThread()

/**
 * Exclusions to be used when downloading libraries that will be loaded at runtime into Wemi process itself.
 * This is because Wemi bundles some libraries already and we don't need to duplicate them.
 */
internal val WemiBundledLibrariesExclude = DefaultExclusions + listOf(
        DependencyExclusion("org.jetbrains.kotlin", "kotlin-stdlib", "*"),
        DependencyExclusion("org.jetbrains.kotlin", "kotlin-reflect", "*")
        //TODO Add Wemi itself here!
)

/** Entry point for the WEMI build tool */
internal fun launch(rootFolder:Path, buildFolder:Path, cacheFolder:Path,
                    cleanBuild:Boolean, interactiveHint:Boolean,
                    machineReadableOutput:Boolean, allowBrokenBuildScripts :Boolean,
                    reloadSupported:Boolean,
                    taskArguments:List<String>, runtimeClasspath:List<Path>) {
    WemiRootFolder = rootFolder
    WemiBuildFolder = buildFolder
    WemiCacheFolder = cacheFolder

    WemiRuntimeClasspath = runtimeClasspath
    WemiReloadSupported = reloadSupported

    var interactive = interactiveHint

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
    PrettyPrinter.setApplicationRootDirectory(WemiRootFolder)

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

    var buildScriptProject = prepareBuildScriptProject(allowBrokenBuildScripts, interactive && !machineReadableOutput, cleanBuild)

    if (buildScriptProject == null) {
        if (!allowBrokenBuildScripts) {
            // Failure to prepare build script (already logged)
            exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
        }

        buildScriptProject = createProjectFromBuildScriptInfo(null)
        LOG.debug("Blank build file loaded")
    }

    // Load build script configuration
    WemiBuildScriptProject = buildScriptProject
    BuildScriptData.AllProjects[buildScriptProject.name] = buildScriptProject

    val errors = buildScriptProject.evaluate { Keys.run.get() }
    if (errors > 0 && !allowBrokenBuildScripts) {
        exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
    }

    // Process initializers
    BuildScriptData.flushInitializers()

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

private fun prepareBuildScriptProject(allowBrokenBuildScripts:Boolean, askUser:Boolean, cleanBuild:Boolean):Project? {
    var preparedProject:Project? = null

    do {
        var keepTrying = false

        val buildScriptSources = findBuildScriptSources(WemiBuildFolder)
        if (buildScriptSources.isEmpty()) {
            if (allowBrokenBuildScripts) {
                LOG.warn("No build script sources found in {}", WemiBuildFolder)
                break
            } else if (!askUser) {
                LOG.error("No build script sources found in {}", WemiBuildFolder)
                break
            } else {
                keepTrying = buildScriptsBadAskIfReload("No build script sources found")
                continue
            }
        }

        preparedProject = directorySynchronized(WemiBuildFolder, {
            LOG.info("Waiting for lock on {}", WemiBuildFolder)
        }) {
            val buildScriptInfo = getBuildScript(WemiCacheFolder, buildScriptSources, cleanBuild)

            if (buildScriptInfo == null) {
                if (allowBrokenBuildScripts) {
                    LOG.warn("Failed to prepare build script")
                } else if (!askUser) {
                    LOG.error("Failed to prepare build script")
                } else {
                    keepTrying = buildScriptsBadAskIfReload("Failed to prepare build script")
                }
                null
            } else {
                LOG.debug("Obtained build script info {}", buildScriptInfo)

                val project = createProjectFromBuildScriptInfo(buildScriptInfo)

                try {
                    project.evaluate { Keys.compile.get() }
                    project
                } catch (ce: WemiException) {
                    val message = ce.message
                    if (ce.showStacktrace || message == null || message.isBlank()) {
                        LOG.error("Build script failed to compile", ce)
                    } else {
                        LOG.error("Build script failed to compile: {}", message)
                        LOG.debug("Build script failed to compile due to", ce)
                    }

                    if (!allowBrokenBuildScripts) {
                        if (!askUser) {
                            LOG.error("Build script failed to compile")
                        } else {
                            keepTrying = buildScriptsBadAskIfReload("Build script failed to compile")
                        }
                    }

                    null
                }
            }
        }
    } while (keepTrying)

    return preparedProject
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
    val allProjects = AllProjects
    when {
        allProjects.isEmpty() -> return null
        else -> {
            var closest: Project? = null
            var closestDist = -1
            projects@for (project in allProjects.values) {
                when {
                    project === WemiBuildScriptProject ||
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
            LOG.trace("findDefaultProject({}) = {}", root, closest)
            return closest
        }
    }
}

internal class ExitWemi(val reload:Boolean) : Exception(null, null, false, false)