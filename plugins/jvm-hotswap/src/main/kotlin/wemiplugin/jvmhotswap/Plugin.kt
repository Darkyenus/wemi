package wemiplugin.jvmhotswap

import org.slf4j.LoggerFactory
import wemi.Command
import wemi.KeyDefaults.inProjectDependencies
import wemi.keys.*
import wemi.WemiException
import wemi.collections.toMutable
import wemi.command
import wemi.configuration
import wemi.key
import wemi.run.ExitCode
import wemi.util.FileSet
import wemi.util.LocatedPath
import wemi.util.Magic
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.isHidden
import wemi.util.matchingLocatedFiles
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.plus
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keys, configurations and their implementations
 */
object JvmHotswap {

    private val LOG = LoggerFactory.getLogger("Hotswap")
    private const val DEFAULT_HOTSWAP_AGENT_PORT = 5015

    val hotswapAgentPort by key("Network port used to communicate with hotswap agent", DEFAULT_HOTSWAP_AGENT_PORT)

    val hotswapping by configuration("Used to execute compile hot-swaps by runHotswap key") {
        outputClassesDirectory modify { dir ->
            dir.parent / "${dir.name}-hotswap"
        }

        runOptions modify {
            val options = it.toMutable()
            val port = hotswapAgentPort.get()
            val agentJar = Magic.classpathFileOf(JvmHotswap.javaClass)!!
            LOG.debug("Agent jar: {}", agentJar)
            options.add("-javaagent:${agentJar.absolutePath}=$port")
            options
        }
    }

    /*
    Communicates with Agent on specified port (Wemi listens as server, client connects)
    Protocol:
    Wemi sends to agent strings - paths that changed.
    They are buffered until empty string is sent, which triggers flush and application of reloads.

    Strings are sent through Java's DataOutputStream.
     */
    val runHotswap: Command<ExitCode> by command("Compile and run the project, watch changes to source files and recompile+hotswap them automatically") {
        var processBuilder: ProcessBuilder? = null
        var sources: FileSet? = null
        var port = DEFAULT_HOTSWAP_AGENT_PORT
        var initialInternalClasspath: List<LocatedPath> = emptyList()
        evaluate(hotswapping) {
            processBuilder = runProcess.get()
            sources = wemi.keys.sources.get().let {
                var result = it
                inProjectDependencies {
                    result += wemi.keys.sources.get()
                }
                result
            }
            port = hotswapAgentPort.get()
            initialInternalClasspath = internalClasspath.get()
        }

        // Start server
        try {
            ServerSocket(port, 1, InetAddress.getLoopbackAddress())
        } catch (e: IOException) {
            throw WemiException("Failed to bind agent-listener to port $port", e)
        }.use { server ->
            // Create initial snapshot of sources
            val sourceIncluded: (LocatedPath) -> Boolean = { !it.file.isHidden() }
            var sourceSnapshot = snapshotFiles(sources.matchingLocatedFiles(), sourceIncluded)
            // Create initial snapshot of internal classpath
            val classpathIncluded: (LocatedPath) -> Boolean = { it.file.name.pathHasExtension("class") }
            var classpathSnapshot = snapshotFiles(initialInternalClasspath, classpathIncluded)

            // Start the process
            // Separate process output from Wemi output
            println()
            val process = processBuilder!!.start()

            val agentSocket = try {
                server.accept()
            } catch (e: IOException) {
                throw WemiException("Failure when waiting for agent to connect on port $port", e)
            }
            val outputStream = DataOutputStream(agentSocket.getOutputStream())

            while (!process.waitFor(2, TimeUnit.SECONDS)) {
                // Process is still running, check filesystem for changes
                val newSourceSnapshot = snapshotFiles(sources.matchingLocatedFiles(), sourceIncluded)
                if (snapshotsAreEqual(newSourceSnapshot, sourceSnapshot)) {
                    // No changes
                    continue
                }
                sourceSnapshot = newSourceSnapshot

                // Recompile
                val newClasspathSnapshot = try {
                    snapshotFiles(evaluate(hotswapping) { internalClasspath.get() }, classpathIncluded)
                } catch (e: WemiException.CompilationException) {
                    LOG.info("Can't swap: {}", e.message)
                    continue
                }

                var changeCount = 0

                // We can't do anything about added classes (those should get picked up automatically),
                // nor removed classes. So just detect what has changed and recompile it.
                for ((key, value) in classpathSnapshot) {
                    if (value == null) {
                        continue
                    }
                    val newHash = newClasspathSnapshot[key] ?: continue
                    if (!MessageDigest.isEqual(value, newHash)) {
                        // This file changed!
                        outputStream.writeUTF(key.file.absolutePath)
                        changeCount++
                    }
                }

                if (changeCount > 0) {
                    outputStream.writeUTF("")
                }

                classpathSnapshot = newClasspathSnapshot

                if (changeCount == 1) {
                    LOG.info("Swapped 1 class")
                } else if (changeCount > 1) {
                    LOG.info("Swapped {} classes", changeCount)
                }
            }

            ExitCode(process.exitValue())
        }
    }
}