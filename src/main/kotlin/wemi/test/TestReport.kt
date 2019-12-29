package wemi.test

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import wemi.WithExitCode
import wemi.util.Json
import wemi.util.JsonSerializer
import wemi.util.field
import wemi.util.fieldToCollection
import wemi.util.writeArray
import wemi.util.writeObject
import wemi.util.writeValue

/**
 * Arbitrary string that is outputted by the test harness, before outputting the TestReport JSON.
 * This is done because debug tools might inject messages that would break the output.
 */
internal const val TEST_LAUNCHER_OUTPUT_PREFIX = "WEMI-TEST-HARNESS-OUTPUT: "

/**
 * Returned by the test.
 *
 * In execution order contains [TestIdentifier]s that were run and [TestData] informing about their execution.
 */
@Json(TestReport.Serializer::class)
class TestReport : LinkedHashMap<TestIdentifier, TestData>(), WithExitCode {

    /** Returns [wemi.boot.EXIT_CODE_SUCCESS] when no tests failed, [wemi.boot.EXIT_CODE_TASK_FAILURE] otherwise. */
    override fun processExitCode(): Int {
        if (values.any { it.status == TestStatus.FAILED }) {
            return wemi.boot.EXIT_CODE_TASK_FAILURE
        }
        return wemi.boot.EXIT_CODE_SUCCESS
    }

    internal class Serializer : JsonSerializer<TestReport> {
        override fun JsonWriter.write(value: TestReport) {
            writeArray {
                value.forEach { identifier, data ->
                    writeObject {
                        field("identifier", identifier)
                        field("data", data)
                    }
                }
            }
        }

        override fun read(value: JsonValue): TestReport {
            val report = TestReport()

            for (entry in value) {
                val identifier = entry.field<TestIdentifier>("identifier")
                val data = entry.field<TestData>("data")

                report[identifier] = data
            }

            return report
        }
    }
}

/**
 * Unique, immutable, test identifier.
 *
 * @param id of the test, not human readable, but unique among other [TestIdentifier]s of the run
 * @param parentId id of the parent [TestIdentifier], if any
 * @param displayName which should be displayed to the user
 *
 * @param isTest if this identifies a test that was executed
 * @param isContainer if this identifies a collection of tests (such collection can still have [TestData])
 * @param tags assigned
 * @param testSource in which this test/container has been found. No content/format is guaranteed, used for debugging.
 */
@Json(TestIdentifier.Serializer::class)
class TestIdentifier(
        val id: String,
        val parentId: String?,
        val displayName: String,

        val isTest: Boolean,
        val isContainer: Boolean,
        val tags: Set<String>,
        val testSource: String?
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TestIdentifier

        if (id != other.id) return false
        if (parentId != other.parentId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (parentId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(id).append(':').append(displayName).append('{')
        if (isTest && isContainer) {
            sb.append("test & container")
        } else if (isTest && !isContainer) {
            sb.append("test")
        } else if (!isTest && isContainer) {
            sb.append("container")
        } else {
            sb.append("nor test nor container")
        }

        if (tags.isNotEmpty()) {
            sb.append(", tags=").append(tags)
        }

        if (testSource != null && testSource.isNotBlank()) {
            sb.append(", source=").append(testSource)
        }
        sb.append('}')

        return sb.toString()
    }

    internal class Serializer : JsonSerializer<TestIdentifier> {
        override fun JsonWriter.write(value: TestIdentifier) {
            writeObject {
                field("id", value.id)
                field("parentId", value.parentId)
                field("displayName", value.displayName)

                field("isTest", value.isTest)
                field("isContainer", value.isContainer)
                name("tags").writeArray {
                    for (tag in value.tags) {
                        writeValue(tag, String::class.java)
                    }
                }
                field("testSource", value.testSource)
            }
        }

        override fun read(value: JsonValue): TestIdentifier {
            return TestIdentifier(
                    value.field("id"),
                    value.field("parentId"),
                    value.field("displayName"),
                    value.field("isTest"),
                    value.field("isContainer"),
                    value.fieldToCollection("tags", HashSet()),
                    value.field("testSource"))
        }
    }
}

/**
 * Results of run of test identified by [TestIdentifier].
 *
 * Mutable.
 */
@Json(TestData.Serializer::class)
class TestData {

    /**
     * Status of this [TestIdentifier]'s run
     */
    var status: TestStatus = TestStatus.NOT_RUN

    /**
     * Duration of the run, in ms.
     * -1 when unknown.
     */
    var duration = -1L

    /**
     * If [status] is [TestStatus.SKIPPED], this may contain why it was skipped
     */
    var skipReason: String? = null

    /**
     * If [status] is [TestStatus.FAILED], this may contain the (possibly filtered) stack trace of the error
     */
    var stackTrace: String? = null

    /**
     * Custom reports made by the test execution.
     *
     * Those contain user data reported by the test.
     *
     * See http://junit.org/junit5/docs/current/user-guide/#writing-tests-dependency-injection TestReporter.
     */
    val reports: ArrayList<ReportEntry> = ArrayList()

    data class ReportEntry(val timestamp: Long, val key: String, val value: String)

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{').append(status)
        if (duration != -1L) {
            sb.append(" in ").append(duration).append(" ms")
        }
        if (skipReason != null) {
            if (status == TestStatus.SKIPPED) {
                sb.append(" reason: ")
            } else {
                sb.append(" skip reason: ")
            }
            sb.append(skipReason)
        }
        val stackTrace = stackTrace
        if (stackTrace != null && stackTrace.isNotBlank()) {
            if (stackTrace.contains('\n')) {
                sb.append(" exception:\n").append(stackTrace).append("\n")
            } else {
                sb.append(" exception:").append(stackTrace).append(' ')
            }
        }
        val reports = reports
        if (reports.isNotEmpty()) {
            sb.append("reports=").append(reports)
        }
        sb.append('}')

        return sb.toString()
    }

    internal class Serializer : JsonSerializer<TestData> {

        override fun JsonWriter.write(value: TestData) {
            writeObject {
                field("status", value.status)
                field("duration", value.duration)
                field("skipReason", value.skipReason)
                field("stackTrace", value.stackTrace)

                name("reports").writeArray {
                    for (report in value.reports) {
                        writeObject {
                            field("timestamp", report.timestamp)
                            field("key", report.key)
                            field("value", report.value)
                        }
                    }
                }
            }
        }

        override fun read(value: JsonValue): TestData {
            return TestData().apply {
                status = value.field("status")
                duration = value.field("duration")
                skipReason = value.field("skipReason")
                stackTrace = value.field("stackTrace")
                value.get("reports")?.forEach { report ->
                    reports.add(ReportEntry(
                            report.getLong("timestamp"),
                            report.getString("key"),
                            report.getString("value")))
                }
            }

        }
    }
}

/**
 * Status of the [TestData].
 */
enum class TestStatus {
    /**
     * Test/collection execution was successful.
     *
     * Note that collection may be successful even if tests contained were not.
     */
    SUCCESSFUL,
    /**
     * Aborted by the user, i.e. the test would be run, but has been stopped for some reason.
     * This does not indicate failure nor success.
     */
    ABORTED,
    /**
     * Test has not been run because it was skipped for some reason.
     * @see TestData.skipReason
     */
    SKIPPED,
    /**
     * Test has been run and failed.
     * @see TestData.stackTrace
     */
    FAILED,
    /**
     * Test has not been run.
     * This is not a standard result and indicates a problem somewhere.
     */
    NOT_RUN
}
