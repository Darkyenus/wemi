package wemi.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wemi.util.DirectorySynchronizedTests.StressTestParameters.CLOSE_PREFIX
import wemi.util.DirectorySynchronizedTests.StressTestParameters.LOG_FILE_NAME
import wemi.util.DirectorySynchronizedTests.StressTestParameters.OPEN_PREFIX
import wemi.util.DirectorySynchronizedTests.StressTestParameters.PROCESSES
import wemi.util.DirectorySynchronizedTests.StressTestParameters.REPEATS
import wemi.util.DirectorySynchronizedTests.StressTestParameters.SLEEP_TIME
import wemi.util.DirectorySynchronizedTests.StressTestParameters.THREADS
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*

/**
 * Tests for [directorySynchronized]
 */
class DirectorySynchronizedTests {

    private val VERBOSE
        get() = true

    @Test
    fun stressTest() {
        if(VERBOSE) println("Directory synchronized test started")
        val directory = Files.createTempDirectory("DirectorySynchronizedTests")
        val log = directory.resolve(LOG_FILE_NAME)
        Files.deleteIfExists(log)
        Files.createFile(log)
        if(VERBOSE) println("Log at: $log")

        val processes = Array(PROCESSES) { i ->
            val command = ArrayList<String>()
            command.add("java")
            command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=${5005 + i}")
            command.add("-cp")
            command.add(System.getProperty("java.class.path"))
            command.add(DirectorySynchronizedMain::class.java.name)
            command.add(i.toString())

            if(VERBOSE) println("Starting process $i $command")
            ProcessBuilder(command).directory(directory.toFile())
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start().apply {
                        outputStream.close()
                    }
        }

        if(VERBOSE) println("All processes created")

        processes.forEachIndexed { index, process ->
            if(VERBOSE) println("Waiting for process $index")
            assertEquals(0, process.waitFor())
        }
        if(VERBOSE) println("All processes ended")

        val seenNumbers = ArrayList<Int>()

        var failures = 0
        var lastOpen = -1
        log.forEachLine { line ->
            if (lastOpen == -1) {
                if (!line.startsWith(OPEN_PREFIX)) {
                    failures++
                }
                lastOpen = line.substring(OPEN_PREFIX.length).toInt()
            } else {
                if (!line.startsWith(CLOSE_PREFIX)) {
                    failures++
                }
                val close = line.substring(CLOSE_PREFIX.length).toInt()
                if (lastOpen != close) {
                    failures++
                }
                lastOpen = -1

                seenNumbers.add(close)
            }
        }
        assertEquals(0, failures, "Failure count")
        assertEquals(-1, lastOpen)

        //if(VERBOSE) println(seenNumbers)
        seenNumbers.sort()
        assertEquals(PROCESSES * THREADS * REPEATS, seenNumbers.size)

        seenNumbers.forEachIndexed { index, i ->
            assertEquals(index, i)
        }

        if(VERBOSE) println("Directory synchronized test ended successfully")
    }

    private object StressTestParameters {
        const val PROCESSES = 10
        const val THREADS = 10
        const val REPEATS = 10
        const val SLEEP_TIME = 1L

        const val LOG_FILE_NAME = "log.txt"
        const val OPEN_PREFIX = "OPEN:  "
        const val CLOSE_PREFIX = "CLOSE: "
    }

    // Started on forked processes
    object DirectorySynchronizedMain {

        private val logFile = Paths.get(LOG_FILE_NAME)

        private fun doSynchronizedDancing(id:Int) {
            directorySynchronized(Paths.get(".")) {
                Files.newOutputStream(logFile, StandardOpenOption.APPEND, StandardOpenOption.WRITE).use {
                    it.write("$OPEN_PREFIX$id\n".toByteArray())
                }

                Thread.sleep(SLEEP_TIME)

                Files.newOutputStream(logFile, StandardOpenOption.APPEND, StandardOpenOption.WRITE).use {
                    it.write("$CLOSE_PREFIX$id\n".toByteArray())
                }
            }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val baseId = args[0].toInt() * THREADS * REPEATS

            val threads = Array(THREADS) { i ->
                Thread({
                    for (repeat in 0 until REPEATS) {
                        doSynchronizedDancing(baseId + i * REPEATS + repeat)
                    }
                }, "Synchronized thread $i").apply {
                    start()
                }
            }

            for (thread in threads) {
                thread.join()
            }
        }
    }
}