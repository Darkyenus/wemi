package wemi.boot

import com.darkyen.tproll.TPLogger
import org.slf4j.LoggerFactory
import java.io.File

private val LOG = LoggerFactory.getLogger("Main")

/**
 * Entry point for the WEMI build tool
 */
fun main(args: Array<String>) {
    var cleanBuild = false

    var errors = false

    val tasks = ArrayList<String>()
    var parsingOptions = true

    for (arg in args) {
        if (parsingOptions) {
            if (arg == "--") {
                parsingOptions = false
            } else if (arg.startsWith("-")) {
                // Parse options
                if (arg == "-clean") {
                    cleanBuild = true
                } else if (arg.startsWith("-log=trace")) {
                    TPLogger.TRACE()
                } else if (arg.startsWith("-log=debug") || arg == "-v" || arg == "-verbose") {
                    TPLogger.DEBUG()
                } else if (arg.startsWith("-log=info")) {
                    TPLogger.INFO()
                } else if (arg.startsWith("-log=warn")) {
                    TPLogger.WARN()
                } else if (arg.startsWith("-log=error")) {
                    TPLogger.WARN()
                } else if (arg == "-?" || arg == "-h" || arg == "-help") {
                    println("WEMI")
                    println("  -clean       Rebuild build files")
                    println("  -log=<trace|debug|info|warn|error>   Set log level")
                } else {
                    LOG.error("Unknown argument {} (-h for list of arguments)", arg)
                    errors = true
                }
            } else {
                tasks.add(arg)
                parsingOptions = false
            }
        } else {
            tasks.add(arg)
        }
    }

    if (errors) {
        System.exit(1)
    }

    // Find root
    val root = File(".").absoluteFile
    val buildFiles = findBuildFile(root)
    if (buildFiles == null) {
        LOG.error("No build files found")
        System.exit(1)
        return
    }

    val compiledBuildFiles = buildFiles.map { getCompiledBuildFile(it, cleanBuild) }

    //TODO Work on all files
    LOG.info("Now we would do crap with these: {}", compiledBuildFiles)
}

