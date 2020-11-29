package wemi.test

import com.esotericsoftware.jsonbeans.JsonException
import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import wemi.util.JsonSerializer
import wemi.util.field
import wemi.util.fieldCollection
import wemi.util.fieldMap
import wemi.util.fieldToCollection
import wemi.util.fieldToMap
import wemi.util.writeArray
import wemi.util.writeObject
import java.util.logging.Level

/** Serializer for [TestReport] class, here because [TestReport] should stay pure Java. */
class TestReportSerializer : JsonSerializer<TestReport> {
	override fun JsonWriter.write(value: TestReport) {
		writeArray {
			for ((identifier, data) in value) {
				writeObject {
					field("identifier", identifier)
					field("data", data)
				}
			}
		}
	}

	override fun read(value: JsonValue): TestReport {
		val result = TestReport()
		if (!value.isArray) {
			throw JsonException("Can't read TestReport from $value")
		}
		for (entry in value) {
			val identifier:TestIdentifier = entry.field("identifier")
			val data:TestData = entry.field("data")
			result[identifier] = data
		}
		return result
	}
}

class TestIdentifierSerializer : JsonSerializer<TestIdentifier> {
	override fun JsonWriter.write(value: TestIdentifier) {
		writeObject {
			field("id", value.id)
			field("parentId", value.parentId)
			field("displayName", value.displayName)
			field("isTest", value.isTest)
			field("isContainer", value.isContainer)
			fieldCollection("tags", value.tags)
			field("testSource", value.testSource)
		}
	}

	override fun read(value: JsonValue): TestIdentifier {
		val id = value.field<String>("id")
		val parentId = value.field<String>("parentId")
		val displayName = value.field<String>("displayName")
		val isTest = value.field<Boolean>("isTest")
		val isContainer = value.field<Boolean>("isContainer")
		val tags = value.fieldToCollection("tags", LinkedHashSet<String>())
		val testSource = value.field<String>("testSource")
		return TestIdentifier(id, parentId, displayName, isTest, isContainer, tags, testSource)
	}
}

class TestDataSerializer : JsonSerializer<TestData> {
	override fun JsonWriter.write(value: TestData) {
		writeObject {
			field("status", value.status)
			field("duration", value.duration)
			field("skipReason", value.skipReason)
			field("stackTrace", value.stackTrace)
			fieldCollection("reports", value.reports)
		}
	}

	override fun read(value: JsonValue): TestData {
		return TestData().apply {
			status = value.field("status")
			duration = value.field("duration")
			skipReason = value.field("skipReason")
			stackTrace = value.field("stackTrace")
			value.fieldToCollection("reports", reports)
		}
	}
}

class ReportEntrySerializer : JsonSerializer<TestData.ReportEntry> {
	override fun JsonWriter.write(value: TestData.ReportEntry) {
		writeObject {
			field("timestamp", value.timestamp)
			field("key", value.key)
			field("value", value.value)
		}
	}

	override fun read(value: JsonValue): TestData.ReportEntry {
		return TestData.ReportEntry(
				value.field("timestamp"),
				value.field("key"),
				value.field("value"))
	}
}

/** Serializer for [TestParameters] class, here because [TestParameters] should stay pure Java. */
class TestParametersSerializer : JsonSerializer<TestParameters> {

	private fun JsonWriter.fieldIncludeExclude(name: String, list: TestParameters.IncludeExcludeList) {
		name(name)
		writeObject {
			fieldCollection("included", list.included)
			fieldCollection("excluded", list.excluded)
		}
	}

	private fun JsonValue.fieldIncludeExclude(name: String, list: TestParameters.IncludeExcludeList) {
		this.get(name)?.let {
			it.fieldToCollection("included", list.included)
			it.fieldToCollection("excluded", list.excluded)
		}
	}

	override fun JsonWriter.write(value: TestParameters) {
		writeObject {
			fieldMap("configuration", value.configuration)
			field("filterStackTraces", value.filterStackTraces)
			fieldCollection("selectPackages", value.selectPackages)
			fieldCollection("selectClasses", value.selectClasses)
			fieldCollection("selectMethods", value.selectMethods)
			fieldCollection("selectResources", value.selectResources)
			fieldCollection("classpathRoots", value.classpathRoots)
			fieldIncludeExclude("filterClassNamePatterns", value.filterClassNamePatterns)
			fieldIncludeExclude("filterPackages", value.filterPackages)
			fieldIncludeExclude("filterTags", value.filterTags)
			field("javaLoggingLevel", value.javaLoggingLevel.intValue())
		}
	}

	override fun read(value: JsonValue): TestParameters {
		return TestParameters().apply {
			value.fieldToMap("configuration", configuration)
			filterStackTraces = value.field("filterStackTraces")
			value.fieldToCollection("selectPackages", selectPackages)
			value.fieldToCollection("selectClasses", selectClasses)
			value.fieldToCollection("selectMethods", selectMethods)
			value.fieldToCollection("selectResources", selectResources)
			value.fieldToCollection("classpathRoots", classpathRoots)
			value.fieldIncludeExclude("filterClassNamePatterns", filterClassNamePatterns)
			value.fieldIncludeExclude("filterPackages", filterPackages)
			value.fieldIncludeExclude("filterTags", filterTags)
			javaLoggingLevel = Level.parse(value.field<Int>("javaLoggingLevel").toString())
		}
	}
}
