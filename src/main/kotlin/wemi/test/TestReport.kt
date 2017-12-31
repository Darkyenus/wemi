package wemi.test

import com.esotericsoftware.jsonbeans.Json
import com.esotericsoftware.jsonbeans.JsonSerializable
import com.esotericsoftware.jsonbeans.JsonSerializer
import com.esotericsoftware.jsonbeans.JsonValue
import wemi.util.putArrayStrings
import wemi.util.writeStringArray

/**
 * Returned by the test
 */
typealias TestReport = Map<TestIdentifier, TestData>

val TestReportSerializer = object : JsonSerializer<TestReport> {

    private fun TestIdentifier.write(json: Json) {
        json.writeValue("id", id, String::class.java)
        json.writeValue("parentId", parentId, String::class.java)
        json.writeValue("displayName", displayName, String::class.java)

        json.writeValue("isTest", isTest, Boolean::class.java)
        json.writeValue("isContainer", isContainer, Boolean::class.java)
        json.writeStringArray(tags, "tags")
        json.writeValue("testSource", testSource, String::class.java)
    }

    override fun write(json: Json, value: TestReport, type: Class<*>?) {
        json.writeArrayStart()
        value.forEach { identifier, data ->
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

    private fun JsonValue.readTestIdentifier():TestIdentifier {
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

    override fun read(json: Json, value: JsonValue, type: Class<*>?): TestReport {
        val result = HashMap<TestIdentifier, TestData>()

        for (entry in value) {
            val identifier = entry.get("identifier").readTestIdentifier()
            val data = TestData().apply { read(json, entry.get("data")) }

            result.put(identifier, data)
        }

        return result
    }
}

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

    //TODO Better toString()
    override fun toString(): String {
        return "TestIdentifier(id='$id', parentId=$parentId, displayName='$displayName', isTest=$isTest, isContainer=$isContainer, tags=$tags, testSource=$testSource)"
    }
}

class TestData : JsonSerializable {

    var status:TestStatus = TestStatus.NOT_RUN

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

    //TODO Better toString()
    override fun toString(): String {
        return "TestData(status=$status, duration=$duration, skipReason=$skipReason, stackTrace=$stackTrace, reports=$reports)"
    }
}

enum class TestStatus {
    SUCCESSFUL,
    ABORTED,
    SKIPPED,
    FAILED,
    NOT_RUN
}
