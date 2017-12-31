package wemi.test

import com.esotericsoftware.jsonbeans.Json
import com.esotericsoftware.jsonbeans.JsonReader
import com.esotericsoftware.jsonbeans.OutputType
import org.slf4j.LoggerFactory
import wemi.dependency.DependencyId
import wemi.dependency.MavenCentral
import wemi.util.LineReadingOutputStream
import wemi.util.readFully
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter

private val LOG = LoggerFactory.getLogger("JUnitPlatform")
private val TEST_OUTPUT_LOG = LoggerFactory.getLogger("TestOutput")

/**
 * Fully qualified class name of the file that contains.
 */
internal const val TEST_LAUNCHER_MAIN_CLASS = "wemi.test.forked.TestLauncherKt"

val JUnitPlatformVersion = "1.0.2"

internal val JUnitPlatformLauncher = DependencyId(
        "org.junit.platform",
        "junit-platform-launcher",
        JUnitPlatformVersion,
        preferredRepository = MavenCentral)

internal fun handleProcessForTesting(builder: ProcessBuilder, testParameters: TestParameters):TestReport? {
    builder.redirectErrorStream(false)
    builder.redirectError(ProcessBuilder.Redirect.PIPE)
    builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)

    LOG.debug("Starting test process")
    val process = builder.start()

    val json = Json(OutputType.json)
    val testParametersJson = json.toJson(testParameters, TestParameters::class.java)

    OutputStreamWriter(process.outputStream, Charsets.UTF_8).use {
        it.write(testParametersJson)
        it.flush()
    }

    val stdout = ByteArrayOutputStream()
    val stderr = LineReadingOutputStream { line ->
        if (line.isNotBlank()) {
            TEST_OUTPUT_LOG.info("{}", line)
        }
    }

    val procStdout = process.inputStream
    val procStderr = process.errorStream

    while (true) {
        val alive = process.isAlive
        readFully(stdout, procStdout)
        readFully(stderr, procStderr)

        if (alive) {
            Thread.sleep(10)
        } else {
            stderr.close()
            break
        }
    }

    val status = process.exitValue()
    if (status == 0) {
        LOG.debug("Test process ended with status 0")
    } else {
        LOG.warn("Test process ended with status {}", status)
    }

    val stdoutString = stdout.toString(Charsets.UTF_8.name())
    LOG.trace("Test process returned stdout: {}", stdoutString)

    return try {
        val stdoutJson = JsonReader().parse(stdoutString)
        if (stdoutJson == null) {
            LOG.error("Failed to parse returned output:\n{}", stdoutString)
            return null
        }

        val report = TestReportSerializer.read(json, stdoutJson, null)
        LOG.debug("Test process returned report: {}", report)

        report
    } catch (e:Exception) {
        LOG.error("Malformed test report output:\n{}", stdoutString, e)
        null
    }
}
