package wemiplugin.jvmhotswap

import org.slf4j.LoggerFactory
import wemi.Command
import wemi.KeyDefaults.inProjectDependencies
import wemi.WemiException
import wemi.boot.CLI
import wemi.collections.toMutable
import wemi.command
import wemi.configuration
import wemi.keys.internalClasspath
import wemi.keys.outputClassesDirectory
import wemi.keys.runOptions
import wemi.keys.runProcess
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
import java.util.concurrent.TimeUnit

/**
 * Keys, configurations and their implementations
 */
object JvmHotswap {

    private val LOG = LoggerFactory.getLogger("Hotswap")

    val recompilingHotswap by configuration("Used when recompiling the hotswapped process") {
        outputClassesDirectory modify { dir ->
            dir.parent / "${dir.name}-hotswap"
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
        // Start server
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        } catch (e: IOException) {
            throw WemiException("Failed to bind agent-listener server socket", e)
        }.use { server ->
            val port = server.localPort

            var processBuilder: ProcessBuilder? = null
            var sources: FileSet? = null
            val initialInternalClasspath = ArrayList<LocatedPath>()

            runOptions modify {
                val options = it.toMutable()
                val agentJar = Magic.classpathFileOf(JvmHotswap.javaClass)!!
                LOG.debug("Agent jar: {}", agentJar)
                options.add("-javaagent:${agentJar.absolutePath}=$port")
                options
            }

            evaluate {
                processBuilder = runProcess.get()
                sources = wemi.keys.sources.get().let {
                    var result = it
                    inProjectDependencies {
                        result += wemi.keys.sources.get()
                    }
                    result
                }
                initialInternalClasspath.addAll(internalClasspath.get())
                inProjectDependencies {
                    initialInternalClasspath.addAll(internalClasspath.get())
                }
            }

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
            CLI.forwardSignalsTo(process) {
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
                        snapshotFiles(evaluate(recompilingHotswap) {
                            val result = ArrayList(internalClasspath.get())
                            inProjectDependencies {
                                result.addAll(internalClasspath.get())
                            }
                            result }, classpathIncluded)
                    } catch (e: WemiException.CompilationException) {
                        LOG.info("Can't swap: {}", e.message)
                        continue
                    }

                    var changeCount = 0

                    // We can't do anything about added classes (those should get picked up automatically),
                    // nor removed classes. So just detect what has changed and recompile it.
                    for ((key, value) in classpathSnapshot) {
                        val (_, oldHash) = value
                        val (newFile, newHash) = newClasspathSnapshot[key] ?: continue
                        if (!oldHash.contentEquals(newHash)) {
                            // This file changed!
                            outputStream.writeUTF(newFile.absolutePath)
                            changeCount++
                        }
                        LOG.debug("{} changed", key)
                    }

                    if (changeCount > 0) {
                        outputStream.writeUTF("")
                    }

                    classpathSnapshot = newClasspathSnapshot
                    LOG.debug("Recompilation done - {} change(s)", changeCount)
                }
            }

            ExitCode(process.exitValue())
        }
    }
}