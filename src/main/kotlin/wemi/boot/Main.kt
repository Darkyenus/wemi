package wemi.boot

import com.darkyen.tproll.TPLogger
import com.darkyen.tproll.integration.JavaLoggingIntegration
import com.darkyen.tproll.logfunctions.ConsoleLogFunction
import com.darkyen.tproll.logfunctions.FileLogFunction
import com.darkyen.tproll.logfunctions.LogFunctionMultiplexer
import org.slf4j.LoggerFactory
import wemi.Configurations
import wemi.WemiVersion
import wemi.util.WemiDefaultClassLoader
import wemi.util.div
import java.io.*
import java.net.URL
import java.net.URLClassLoader
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Main")

val EXIT_CODE_SUCCESS = 0
@Suppress("unused")
@Deprecated("Consider adding separate error code")
val EXIT_CODE_UNKNOWN_ERROR = 1
val EXIT_CODE_ARGUMENT_ERROR = 2
val EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR = 3
val EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR = 4
val EXIT_CODE_MACHINE_OUTPUT_NO_PROJECT_ERROR = 5
val EXIT_CODE_MACHINE_OUTPUT_NO_CONFIGURATION_ERROR = 6
val EXIT_CODE_MACHINE_OUTPUT_NO_KEY_ERROR = 7
val EXIT_CODE_MACHINE_OUTPUT_KEY_NOT_SET_ERROR = 8

/**
 * Entry point for the WEMI build tool
 */
fun main(args: Array<String>) {
    TPLogger.attachUnhandledExceptionLogger()
    JavaLoggingIntegration.enable()

    var cleanBuild = false

    var errors = 0

    val tasks = ArrayList<String>()
    var interactive = false
    var machineReadableOutput = false
    var allowBrokenBuildScripts = false

    var parsingOptions = true

    for (arg in args) {
        if (parsingOptions) {
            if (arg == "--") {
                parsingOptions = false
            } else if (arg.startsWith("-")) {
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
                } else if (arg == "-i" || arg == "-interactive") {
                    interactive = true
                } else if (arg == "-machineReadableOutput") {
                    machineReadableOutput = true
                } else if (arg == "-allowBrokenBuildScripts") {
                    allowBrokenBuildScripts = true
                } else if (arg == "-v" || arg == "-version") {
                    println("WEMI $WemiVersion with Kotlin $KotlinVersion")
                } else if (arg == "-?" || arg == "-h" || arg == "-help") {
                    println("WEMI")
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
                    println("  -v[ersion]")
                    println("      Print version")
                } else {
                    LOG.error("Unknown argument {} (-h for list of arguments)", arg)
                    errors++
                }
            } else {
                tasks.add(arg)
                parsingOptions = false
            }
        } else {
            tasks.add(arg)
        }
    }

    val machineOutput: PrintStream?

    if (machineReadableOutput) {
        // Redirect logging to err
        machineOutput = PrintStream(FileOutputStream(FileDescriptor.out))
        System.setOut(System.err)
    } else {
        machineOutput = null

        if (tasks.isEmpty()) {
            interactive = true
        }
    }


    if (errors > 0) {
        exitProcess(EXIT_CODE_ARGUMENT_ERROR)
    }

    // Find root
    val root = File(".").absoluteFile

    val buildFolder = root / "build"
    val buildScriptSources = findBuildScriptSources(buildFolder)

    val consoleLogger = ConsoleLogFunction(null, null)
    if (buildScriptSources.isEmpty()) {
        LOG.warn("No build files found")

        TPLogger.setLogFunction(consoleLogger)
    } else {
        LOG.debug("{} build file(s) found", buildScriptSources.size)

        TPLogger.setLogFunction(LogFunctionMultiplexer(
                FileLogFunction(buildFolder / "logs"),
                consoleLogger
        ))
    }

    val buildFile = getCompiledBuildScript(buildFolder, buildScriptSources, cleanBuild)

    if (!allowBrokenBuildScripts && buildFile == null) {
        LOG.warn("Build script failed to compile")
        exitProcess(EXIT_CODE_BUILD_SCRIPT_COMPILATION_ERROR)
    }

    // Load build files now
    if (buildFile != null) {
        val urls = arrayOfNulls<URL>(2 + buildFile.classpath.size)
        urls[0] = buildFile.scriptJar.toURI().toURL()
        var i = 1
        for (file in buildFile.classpath) {
            urls[i++] = file.toURI().toURL()
        }
        val loader = URLClassLoader(urls, WemiDefaultClassLoader)
        LOG.debug("Loading build file {}", buildFile)
        BuildScriptIntrospection.currentlyInitializedBuildScript = buildFile
        for (initClass in buildFile.initClasses) {
            try {
                Class.forName(initClass, true, loader)
            } catch (e: Exception) {
                LOG.warn("Failed to load build file class {} from {}", initClass, urls, e)
            }
        }
        BuildScriptIntrospection.currentlyInitializedBuildScript = null
        LOG.debug("Build file loaded")
    }

    // - Ensure Configurations are loaded -
    Configurations
    // ------------------------------------

    if (machineReadableOutput) {
        val out = machineOutput!!
        for (task in tasks) {
            machineReadableEvaluateAndPrint(out, task)
        }

        if (interactive) {
            val reader = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = reader.readLine() ?: break
                machineReadableEvaluateAndPrint(out, line)
            }
        }
    } else {
        CLI.init(root)

        for (task in tasks) {
            CLI.evaluateKeyAndPrint(task)
        }

        if (interactive) {
            CLI.beginInteractive()
        }
    }

    exitProcess(EXIT_CODE_SUCCESS)
}