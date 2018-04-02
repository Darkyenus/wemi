package wemi.test

import com.esotericsoftware.jsonbeans.Json
import com.esotericsoftware.jsonbeans.JsonSerializable
import com.esotericsoftware.jsonbeans.JsonValue
import wemi.WithExitCode
import wemi.boot.MachineWritable
import wemi.util.putArrayStrings
import wemi.util.writeStringArray

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
class TestReport : LinkedHashMap<TestIdentifier, TestData>(), JsonSerializable, MachineWritable, WithExitCode {
    private fun TestIdentifier.write(json: Json) {
        json.writeValue("id", id, String::class.java)
        json.writeValue("parentId", parentId, String::class.java)
        json.writeValue("displayName", displayName, String::class.java)

        json.writeValue("isTest", isTest, Boolean::class.java)
        json.writeValue("isContainer", isContainer, Boolean::class.java)
        json.writeStringArray(tags, "tags")
        json.writeValue("testSource", testSource, String::class.java)
    }

    override fun write(json: Json) {
        json.writeArrayStart()
        forEach { identifier, data ->
            json.writeObjectStart()

            json.writeObjectStart("identifier")
            identifier.write(json)
            json.writeObjectEnd()

            json.writeObjectStart("data")
            data.write(json)
            json.writeObjectEnd()

            json.writeObjectEnd()
        }
        json.writeArrayEnd()
    }

    private fun JsonValue.readTestIdentifier(): TestIdentifier {
        val id = this.getString("id")
        val parentId = this.getString("parentId")
        val displayName = this.getString("displayName")

        val isTest = this.getBoolean("isTest")
        val isContainer = this.getBoolean("isContainer")
        val tags = HashSet<String>()
        this.get("tags")?.putArrayStrings(tags)
        val testSource = this.getString("testSource")

        return TestIdentifier(id, parentId, displayName, isTest, isContainer, tags, testSource)
    }

    override fun read(json: Json, value: JsonValue) {
        for (entry in value) {
            val identifier = entry.get("identifier").readTestIdentifier()
            val data = TestData().apply { read(json, entry.get("data")) }

            put(identifier, data)
        }
    }

    override fun writeMachine(json: Json) {
        write(json)
    }

    /**
     * Returns [wemi.boot.EXIT_CODE_SUCCESS] when all tests are either successful or skipped.
     * [wemi.boot.EXIT_CODE_TASK_FAILURE] otherwise.
     */
    override fun processExitCode(): Int {
        if (values.all { it.status == TestStatus.SUCCESSFUL || it.status == TestStatus.SKIPPED }) {
            return wemi.boot.EXIT_CODE_SUCCESS
        }
        return wemi.boot.EXIT_CODE_TASK_FAILURE
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
}

/**
 * Results of run of test identified by [TestIdentifier].
 *
 * Mutable.
 */
class TestData : JsonSerializable {

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

    override fun write(json: Json) {
        json.writeValue("status", status, TestStatus::class.java)
        json.writeValue("duration", duration, Long::class.java)
        json.writeValue("skipReason", skipReason, String::class.java)
        json.writeValue("stackTrace", stackTrace, String::class.java)
        json.writeArrayStart("reports")
        for (report in reports) {
            json.writeObjectStart()
            json.writeValue("timestamp", report.timestamp, Long::class.java)
            json.writeValue("key", report.key, String::class.java)
            json.writeValue("value", report.value, String::class.java)
            json.writeObjectEnd()
        }
        json.writeArrayEnd()
    }

    override fun read(json: Json, value: JsonValue) {
        status = json.readValue("status", TestStatus::class.java, value)
        duration = value.getLong("duration")
        skipReason = value.getString("skipReason")
        stackTrace = value.getString("stackTrace")
        value.get("reports")?.forEach { report ->
            reports.add(ReportEntry(
                    report.getLong("timestamp"),
                    report.getString("key"),
                    report.getString("value")))
        }
    }

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
