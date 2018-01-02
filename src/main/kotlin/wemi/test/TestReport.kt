package wemi.test

import com.esotericsoftware.jsonbeans.Json
import com.esotericsoftware.jsonbeans.JsonSerializable
import com.esotericsoftware.jsonbeans.JsonValue
import wemi.WithExitCode
import wemi.boot.MachineWritable
import wemi.util.putArrayStrings
import wemi.util.writeStringArray

/**
 * Returned by the test
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
 * Unique, immutable, test identifier
 */
class TestIdentifier(
        val id:String,
        val parentId:String?,
        val displayName:String,

        val isTest:Boolean,
        val isContainer:Boolean,
        val tags:Set<String>,
        val testSource:String?
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
 */
class TestData : JsonSerializable {

    var status: TestStatus = TestStatus.NOT_RUN

    var duration = -1L

    var skipReason:String? = null

    var stackTrace:String? = null

    val reports: ArrayList<ReportEntry> = ArrayList(0)

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

    data class ReportEntry(val timestamp:Long, val key:String, val value:String)

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

enum class TestStatus {
    SUCCESSFUL,
    ABORTED,
    SKIPPED,
    FAILED,
    NOT_RUN
}
