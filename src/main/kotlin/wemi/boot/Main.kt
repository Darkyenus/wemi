package wemi.boot

import com.darkyen.tproll.TPLogger
import com.darkyen.tproll.integration.JavaLoggingIntegration
import com.darkyen.tproll.logfunctions.ConsoleLogFunction
import com.darkyen.tproll.logfunctions.FileLogFunction
import com.darkyen.tproll.logfunctions.LogFunctionMultiplexer
import org.slf4j.LoggerFactory
import wemi.CLI
import wemi.util.WemiClasspathFile
import wemi.util.WemiDefaultClassLoader
import wemi.util.div
import java.io.File
import java.net.URL
import java.net.URLClassLoader

private val LOG = LoggerFactory.getLogger("Main")

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
                    TPLogger.WARN()
                } else if (arg == "-i" || arg == "-interactive") {
                    interactive = true
                } else if (arg == "-?" || arg == "-h" || arg == "-help") {
                    println("WEMI")
                    println("  -clean       Rebuild build files")
                    println("  -log=<trace|debug|info|warn|error>   Set log level")
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

    if (tasks.isEmpty()) {
        interactive = true
    }

    if (errors > 0) {
        System.exit(1)
    }

    // Find root
    val root = File(".").absoluteFile
    val buildFiles = findBuildFile(root)
    if (buildFiles.isEmpty()) {
        LOG.error("No build files found")
        System.exit(1)
        return
    }

    TPLogger.setLogFunction(LogFunctionMultiplexer(
        FileLogFunction(prepareBuildFileCacheFolder(buildFiles.first())!! / "logs"),
        ConsoleLogFunction(null, null)
    ))

    val compiledBuildFiles = mutableListOf<BuildFile>()

    for (buildFile in buildFiles) {
        val compiled = getCompiledBuildFile(buildFile, cleanBuild)
        if (compiled == null) {
            errors++
        } else {
            compiledBuildFiles.add(compiled)
        }
    }

    if (errors > 0) {
        LOG.warn("{} build script(s) failed to compile", errors)
        System.exit(1)
    }

    // Load build files now
    for (buildFile in compiledBuildFiles) {
        val urls = arrayOfNulls<URL>(2 + buildFile.extraClasspath.size)
        urls[0] = buildFile.scriptJar.toURI().toURL()
        urls[1] = WemiClasspathFile.toURI().toURL()
        var i = 2
        for (file in buildFile.extraClasspath) {
            urls[i++] = file.toURI().toURL()
        }
        val loader = URLClassLoader(urls, WemiDefaultClassLoader)
        LOG.debug("Loading build file {}", buildFile)
        Class.forName(buildFile.initClass, true, loader)
        LOG.debug("Build file loaded")
    }

    CLI.init(root)

    for (task in tasks) {
        CLI.evaluateKeyAndPrint(task)
    }

    if (interactive) {
        CLI.beginInteractive()
    }
}