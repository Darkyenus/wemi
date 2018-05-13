package wemi.test

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import wemi.util.*

/**
 * Parameters for the test run, using JUnit Platform.
 */
@Json(TestParameters.Serializer::class)
class TestParameters {

    /**
     * The configuration parameters to be used.
     */
    val configuration: MutableMap<String, String> = HashMap()

    /**
     * Whether or not shown stack-traces should be filtered.
     * This involves removing the entries that are inside the JUnit framework and don't contribute much value
     * to the regular user.
     *
     * True by default.
     */
    var filterStackTraces = true

    val select = Selectors()

    val filter = Filters()

    class Selectors internal constructor() {
        /**
         * A list of URIs that are to be used for test discovery.
         */
        val uris = mutableListOf<String>()

        /**
         * A list of files that are to be used for test discovery.
         */
        val files = mutableListOf<String>()

        /**
         * A list of directories that are to be used for test discovery.
         */
        val directories = mutableListOf<String>()

        /**
         * A list of packages that are to be used for test discovery.
         */
        val packages = mutableListOf<String>()

        /**
         * A list of classes that are to be used for test discovery.
         */
        val classes = mutableListOf<String>()

        /**
         * A list of methods that are to be used for test discovery.
         */
        val methods = mutableListOf<String>()

        /**
         * A list of classpath resources that are to be used for test discovery.
         */
        val resources = mutableListOf<String>()

        /**
         * A list of classpath roots that are to be used for test discovery.
         */
        val classpathRoots = mutableListOf<String>()

        fun isEmpty(): Boolean {
            return uris.isEmpty()
                    && files.isEmpty()
                    && directories.isEmpty()
                    && packages.isEmpty()
                    && classes.isEmpty()
                    && methods.isEmpty()
                    && resources.isEmpty()
        }

        override fun toString(): String {
            return "(uris=$uris, files=$files, directories=$directories, packages=$packages, classes=$classes, methods=$methods, resources=$resources, classpathRoots=$classpathRoots)"
        }
    }

    /**
     * Additional filtering on the selected tests.
     *
     * Patterns are combined using OR semantics, i.e. when at least one pattern matches,
     * the matched item will be included/excluded from the test plan.
     */
    class Filters internal constructor() {
        /**
         * Engine IDs to be included/excluded when building the test plan.
         */
        val engines = IncludeExcludeList()

        /**
         * List of regular expression patterns to be matched against fully classified class names,
         * to determine which classes should be included/excluded in the test plan.
         *
         * When [IncludeExcludeList.included] is empty,
         * [org.junit.platform.engine.discovery.ClassNameFilter.STANDARD_INCLUDE_PATTERN] is used as default.
         */
        val classNamePatterns = IncludeExcludeList()

        /**
         * A list of packages to be included/excluded when building the test plan.
         * Applies to sub-packages as well.
         */
        val packages = IncludeExcludeList()

        /**
         * A list of tags to be included/excluded when building the test plan.
         */
        val tags = IncludeExcludeList()

        override fun toString(): String {
            return "(engines=$engines, classNamePatterns=$classNamePatterns, packages=$packages, tags=$tags)"
        }
    }

    internal class Serializer : JsonSerializer<TestParameters> {
        override fun JsonWriter.write(value: TestParameters) {
            writeObject {
                // Configuration
                fieldMap("configuration", value.configuration)

                field("filterStackTraces", value.filterStackTraces)

                // Selector
                name("selector").writeObject {
                    fieldCollection("uris", value.select.uris)
                    fieldCollection("files", value.select.files)
                    fieldCollection("directories", value.select.directories)
                    fieldCollection("packages", value.select.packages)
                    fieldCollection("classes", value.select.classes)
                    fieldCollection("methods", value.select.methods)
                    fieldCollection("resources", value.select.resources)
                    fieldCollection("classpathRoots", value.select.classpathRoots)
                }

                // Filter
                name("filter").writeObject {
                    field("engines", value.filter.engines)
                    field("classNamePatterns", value.filter.classNamePatterns)
                    field("packages", value.filter.packages)
                    field("tags", value.filter.tags)
                }
            }
        }

        override fun read(value: JsonValue): TestParameters {
            val result = TestParameters()

            // Configuration
            value.fieldToMap("configuration", result.configuration)

            result.filterStackTraces = value.field("filterStackTraces")

            // Selector
            value.get("selector")?.let { selectorValue ->
                selectorValue.fieldToCollection("uris", result.select.uris)
                selectorValue.fieldToCollection("files", result.select.files)
                selectorValue.fieldToCollection("directories", result.select.directories)
                selectorValue.fieldToCollection("packages", result.select.packages)
                selectorValue.fieldToCollection("classes", result.select.classes)
                selectorValue.fieldToCollection("methods", result.select.methods)
                selectorValue.fieldToCollection("resources", result.select.resources)
                selectorValue.fieldToCollection("classpathRoots", result.select.classpathRoots)
            }

            // Filter
            value.get("filter")?.let { filterValue ->
                filterValue.fieldTo("engines", result.filter.engines)
                filterValue.fieldTo("classNamePatterns", result.filter.classNamePatterns)
                filterValue.fieldTo("packages", result.filter.packages)
                filterValue.fieldTo("tags", result.filter.tags)
            }

            return result
        }
    }

    override fun toString(): String {
        return "TestParameters(configuration=$configuration, select=$select, filter=$filter)"
    }

    @Suppress("unused")
    /**
     * Mutable collection coupling included and excluded names/patterns.
     */
    class IncludeExcludeList : JsonReadable, JsonWritable {
        /**
         * Names/patterns that are included.
         */
        val included: MutableList<String> = ArrayList()
        /**
         * Names/patterns that are excluded.
         */
        val excluded: MutableList<String> = ArrayList()

        /**
         * Add [item] to included items.
         */
        fun include(item: String) {
            included.add(item)
        }

        /**
         * Add [items] to included items.
         */
        fun include(vararg items: String) {
            included.addAll(items)
        }

        /**
         * Add [item] to excluded items.
         */
        fun exclude(item: String) {
            excluded.add(item)
        }

        /**
         * Add [items] to excluded items.
         */
        fun exclude(vararg items: String) {
            excluded.addAll(items)
        }

        /**
         * @return true if both [included] and [excluded] are empty
         */
        fun isEmpty(): Boolean = included.isEmpty() && excluded.isEmpty()

        override fun toString(): String {
            return "+$included -$excluded"
        }

        override fun JsonWriter.write() {
            writeObject {
                fieldCollection("included", included)
                fieldCollection("excluded", excluded)
            }
        }

        override fun read(value: JsonValue) {
            value.fieldToCollection("included", included)
            value.fieldToCollection("excluded", excluded)
        }
    }
}