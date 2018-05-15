package wemiplugin.jvmhotswap

import wemi.*
import wemi.util.*
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keys, configurations and their implementations
 */
object JvmHotswap {

    val runHotswap by key<Int>("Compile and run the project, watch changes to source files and recompile+hotswap them automatically, return exit code")

    val hotswapAgentPort by key<Int>("Network port used to communicate with hotswap agent")

    val hotswapping by configuration("Used to execute compile hot-swaps by runHotswap key") {
        Keys.outputClassesDirectory modify { dir ->
            dir.parent / "${dir.name}-hotswap"
        }
    }

    object Defaults {

        /*
        Communicates with Agent on specified port (Wemi listens as server, client connects)
        Protocol:
        Wemi sends to agent strings - paths that changed.
        They are buffered until empty string is sent, which triggers flush and application of reloads.

        Strings are sent through Java's DataOutputStream.
         */

        val RunHotswap:BoundKeyValue<Int> = {
            using(Configurations.running, hotswapping) {
                val javaExecutable = Keys.javaExecutable.get()
                val classpathEntries = LinkedHashSet<Path>()
                for (locatedFile in Keys.externalClasspath.get()) {
                    classpathEntries.add(locatedFile.classpathEntry)
                }
                val initialInternalClasspath = Keys.internalClasspath.get()
                for (locatedFile in initialInternalClasspath) {
                    classpathEntries.add(locatedFile.classpathEntry)
                }
                val directory = Keys.runDirectory.get()
                val mainClass = Keys.mainClass.get()
                val options = Keys.runOptions.get().toMutable()
                val port = hotswapAgentPort.get()
                val agentJar = Magic.classpathFileOf(JvmHotswap.javaClass)!!
                options.add("-javaagent:${agentJar.absolutePath}=$port")

                val arguments = Keys.runArguments.get()

                val processBuilder = wemi.run.prepareJavaProcess(javaExecutable, directory, classpathEntries,
                        mainClass, options, arguments)

                // Start server
                val server = try {
                    ServerSocket(port, 1, InetAddress.getLoopbackAddress())
                } catch (e: IOException) {
                    throw WemiException("Failed to bind agent-listener to port $port", e)
                }

                // Create initial snapshot of sources
                val sourceIncluded: (LocatedPath) -> Boolean = { !it.file.isHidden() }
                var sourceSnapshot = snapshotFiles(Keys.sourceFiles.get(), sourceIncluded)
                // Create initial snapshot of internal classpath
                val classpathIncluded: (LocatedPath) -> Boolean = { it.file.name.pathHasExtension("class") }
                var classpathSnapshot = snapshotFiles(initialInternalClasspath, classpathIncluded)

                // Start the process
                // Separate process output from Wemi output
                println()
                val process = processBuilder.start()

                val agentSocket = try {
                    server.accept()
                } catch (e:IOException) {
                    throw WemiException("Failure when waiting for agent to connect on port $port", e)
                }
                val outputStream = DataOutputStream(agentSocket.getOutputStream())

                while (!process.waitFor(2, TimeUnit.SECONDS)) {
                    // Process is still running, check filesystem for changes
                    cleanEvaluationCache(false)
                    val newSourceSnapshot = snapshotFiles(Keys.sourceFiles.get(), sourceIncluded)
                    if (snapshotsAreEqual(newSourceSnapshot, sourceSnapshot)) {
                        // No changes
                        continue
                    }
                    sourceSnapshot = newSourceSnapshot

                    // Recompile
                    val newClasspathSnapshot = snapshotFiles(Keys.internalClasspath.get(), classpathIncluded)

                    var anyChangesToFlush = false

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
                            anyChangesToFlush = true
                        }
                    }

                    if (anyChangesToFlush) {
                        outputStream.writeUTF("")
                    }

                    classpathSnapshot = newClasspathSnapshot
                }

                process.exitValue()
            }
        }

    }
}