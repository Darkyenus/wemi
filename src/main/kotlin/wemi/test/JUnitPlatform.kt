package wemi.test

import org.slf4j.LoggerFactory
import wemi.Scope
import wemi.boot.CLI
import wemi.dependency.Dependency
import wemi.dependency.DependencyId
import wemi.dependency.MavenCentral
import wemi.test.TestStatus.*
import wemi.util.*
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.concurrent.TimeUnit

private val LOG = LoggerFactory.getLogger("JUnitPlatform")
private val TEST_OUTPUT_LOG = LoggerFactory.getLogger("TestOutput")

/**
 * Fully qualified class name of the file that contains.
 */
internal const val TEST_LAUNCHER_MAIN_CLASS = "wemi.test.forked.TestLauncherKt"

/**
 * Currently used JUnit Platform version
 */
const val JUnitPlatformVersion = "1.0.2"

/**
 * Currently used JUnit Jupiter api/engine version
 */
const val JUnitEngineVersion = "5.0.2"

/**
 * Dependency on JUnit 5 API
 *
 * To use JUnit 5 tests, add this as a testing dependency, together with [JUnitEngine].
 *
 * @see [JUnitEngineVersion] for used version (based on Wemi version)
 */
@Suppress("unused")//Scope.
val Scope.JUnitAPI: Dependency
    get() = Dependency(DependencyId("org.junit.jupiter", "junit-jupiter-api", JUnitEngineVersion, MavenCentral))

/**
 * Dependency on JUnit 5 Engine
 *
 * To use JUnit 5 tests, add this as a testing dependency, together with [JUnitAPI].
 *
 * @see [JUnitEngineVersion] for used version (based on Wemi version)
 */
@Suppress("unused")//Scope.
val Scope.JUnitEngine: Dependency
    get() = Dependency(DependencyId("org.junit.jupiter", "junit-jupiter-engine", JUnitEngineVersion, MavenCentral))

/**
 * Dependency on JUnit 4 Engine
 *
 * To use JUnit 4 tests, add this as a testing dependency, together with JUnit 4.
 */
@Suppress("unused")//Scope. and not auto-imported
val Scope.JUnit4Engine: Dependency
    get() = Dependency(DependencyId("org.junit.vintage", "junit-vintage-engine", "4.12.2", MavenCentral))

/**
 * DependencyId for the launcher needed to execute tests based on JUnit platform.
 */
internal val JUnitPlatformLauncher = DependencyId(
        "org.junit.platform",
        "junit-platform-launcher",
        JUnitPlatformVersion,
        preferredRepository = MavenCentral)

/**
 * Handle running the process that does testing.
 * In particular, this sends the process [testParameters], logs its output and receives and returns
 * created [TestReport].
 */
internal fun handleProcessForTesting(builder: ProcessBuilder, testParameters: TestParameters): TestReport? {
    builder.redirectErrorStream(false)
    builder.redirectError(ProcessBuilder.Redirect.PIPE)
    builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)

    LOG.debug("Starting test process")
    val process = builder.start()

    OutputStreamWriter(process.outputStream, Charsets.UTF_8).use {
        it.writeJson(testParameters, TestParameters::class.java)
    }

    var outputJson = ""

    val stdout = LineReadingOutputStream { line ->
        if (line.startsWith(TEST_LAUNCHER_OUTPUT_PREFIX)) {
            outputJson = line.substring(TEST_LAUNCHER_OUTPUT_PREFIX.length)
            LOG.trace("Test process returned json: {}", outputJson)
        } else {
            val trimmedLine = line.dropLastWhile { it.isWhitespace() }
            if (trimmedLine.isNotEmpty()) {
                TEST_OUTPUT_LOG.info("{}", trimmedLine)
            }
        }
    }
    val stderr = LineReadingOutputStream { line ->
        val trimmedLine = line.dropLastWhile { it.isWhitespace() }
        if (trimmedLine.isNotEmpty()) {
            TEST_OUTPUT_LOG.info("{}", trimmedLine)
        }
    }

    val procStdout = process.inputStream
    val procStderr = process.errorStream

    try {
        while (true) {
            val exited = process.waitFor(10, TimeUnit.MILLISECONDS)
            readFully(stdout, procStdout)
            readFully(stderr, procStderr)

            if (exited) {
                break
            }
        }
    } finally {
        stdout.close()
        stderr.close()
    }

    val status = process.exitValue()
    if (status == 0) {
        LOG.debug("Test process ended with status 0")
    } else {
        LOG.warn("Test process ended with status {}", status)
    }

    return try {
        val report = fromJson<TestReport>(outputJson)
        LOG.debug("Test process returned report: {}", report)
        report
    } catch (e: Exception) {
        LOG.error("Malformed test report output:\n{}", outputJson, e)
        null
    }
}

/**
 * Creates a human readable, ANSI-colored (if supported), tree with TestReport execution overview,
 * including a summary at the end.
 */
fun TestReport.prettyPrint(): CharSequence {
    val keys = keys.toMutableList()
    keys.sortBy { it.id }

    val roots = ArrayList<TreeNode<TestIdentifier>>()
    val stack = ArrayDeque<TreeNode<TestIdentifier>>()

    fun popUntilChild(identifier: TestIdentifier): TreeNode<TestIdentifier>? {
        if (stack.isEmpty()) {
            return null
        }

        while (true) {
            val top = stack.peekFirst()
            if (identifier.id.startsWith(top.value.id)) {
                // Top is a parent of identifier
                return top
            }

            // Top is not a parent, we must pop
            val popped = stack.removeFirst()
            if (stack.isEmpty()) {
                roots.add(popped)
                return null
            }
        }
    }

    // Build trees
    for (identifier in keys) {
        val parent = popUntilChild(identifier)
        if (parent == null) {
            // identifier is a root and has no parent
            stack.addFirst(TreeNode(identifier))
        } else {
            // identifier is a child of parent
            val node = TreeNode(identifier)
            parent.add(node)
            stack.addFirst(node)
        }
    }

    // Collect last tree
    if (stack.isNotEmpty()) {
        roots.add(stack.peekLast())
    }

    // Time to print
    val result = StringBuilder()
    val tree = printTree(roots) { sb ->
        val data = this@prettyPrint[this]!!

        // Name (+ test/container)
        sb.format(if (isContainer) Color.White else null, format = if (isTest) Format.Bold else null)
        sb.append(if (displayName.isBlank()) id else displayName)
        sb.format()

        // Status
        sb.append(' ')
        when (data.status) {
            SUCCESSFUL -> sb.append(CLI.ICON_SUCCESS)
            ABORTED -> sb.append(CLI.ICON_ABORTED)
            SKIPPED -> sb.append(CLI.ICON_SKIPPED)
            FAILED -> sb.append(CLI.ICON_FAILURE)
            NOT_RUN -> sb.append(CLI.ICON_UNKNOWN)
        }

        // Skip reason
        val skipReason = data.skipReason
        if (skipReason != null) {
            sb.append(' ').format(Color.White).append(skipReason).format()
        }

        // Timing
        if (data.duration > 0L) {
            sb.append(' ').format(Color.Cyan).appendTimeDuration(data.duration).format()
        }

        // Stack trace
        val stackTrace = data.stackTrace
        if (stackTrace != null) {
            sb.append('\n')
            sb.append(stackTrace)
        }

        // Reports
        var lastTimestamp = -1L
        for (report in data.reports) {
            sb.append('\n')
            if (report.timestamp != lastTimestamp) {
                lastTimestamp = report.timestamp
                val instant = Instant.ofEpochMilli(report.timestamp)
                val dateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
                sb.append(dateTime.toLocalDate()).append(' ').append(dateTime.toLocalTime()).append(':').append('\n')
            }
            sb.append(" ")
            sb.append(report.key)
            sb.format(Color.White).append(" = \"").format(Color.Blue)
            sb.append(report.value)
            sb.format(Color.White).append('"').format()
        }
    }

    result.append(tree)
    // Do the status report

    var containersFound = 0
    var containersSkipped = 0
    var containersAborted = 0
    var containersSuccessful = 0
    var containersFailed = 0

    var testsFound = 0
    var testsSkipped = 0
    var testsAborted = 0
    var testsSuccessful = 0
    var testsFailed = 0

    for ((identifier, data) in entries) {
        if (identifier.isContainer) {
            containersFound++
            @Suppress("NON_EXHAUSTIVE_WHEN")
            when (data.status) {
                SUCCESSFUL -> containersSuccessful++
                ABORTED -> containersAborted++
                SKIPPED -> containersSkipped++
                FAILED -> containersFailed++
            }
        }

        if (identifier.isTest) {
            testsFound++
            @Suppress("NON_EXHAUSTIVE_WHEN")
            when (data.status) {
                SUCCESSFUL -> testsSuccessful++
                ABORTED -> testsAborted++
                SKIPPED -> testsSkipped++
                FAILED -> testsFailed++
            }
        }
    }

    result.append("\n           - Summary -           \n")
    result.appendReport(containersFound, "container", "found", false)
    result.appendReport(containersSkipped, "container", "skipped", null)
    result.appendReport(containersAborted, "container", "aborted", null)
    result.appendReport(containersSuccessful, "container", "successful", false)
    result.appendReport(containersFailed, "container", "failed", true)
    result.appendReport(testsFound, "test", "found", false)
    result.appendReport(testsSkipped, "test", "skipped", null)
    result.appendReport(testsAborted, "test", "aborted", null)
    result.appendReport(testsSuccessful, "test", "successful", false)
    result.appendReport(testsFailed, "test", "failed", true)
    return result
}

/** Mimics default JUnit report sheet. */
private fun StringBuilder.appendReport(amount:Int, noun:String, action:String, zeroIsGood:Boolean?) {
    val reportWidth = 33
    var initialLength = length

    append('[')

    initialLength -= length
    if (amount != 0 && zeroIsGood != null) {
        if (zeroIsGood) {
            this.format(foreground = wemi.util.Color.Red)
        } else {
            this.format(foreground = wemi.util.Color.Green)
        }
    }
    initialLength += length

    appendPadded(amount, 7, ' ')

    initialLength -= length
    this.format()
    initialLength += length

    this.append(' ').append(noun)

    if (amount != 1) {
        append('s')
    }
    append(' ').append(action)

    appendTimes(' ', initialLength + reportWidth - 1 - length)
        .append(']').append('\n')
}
