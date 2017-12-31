package wemi.test

import com.esotericsoftware.jsonbeans.Json
import com.esotericsoftware.jsonbeans.JsonSerializable
import com.esotericsoftware.jsonbeans.JsonValue
import wemi.util.putArrayStrings
import wemi.util.writeStringArray

/**
 * Parameters for the test run, using JUnit Platform.
 */
class TestParameters : JsonSerializable {

    /**
     * The configuration parameters to be used.
     */
    val configuration:MutableMap<String, String> = HashMap()

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

        fun isEmpty():Boolean {
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



    override fun write(json: Json) {
        // Configuration
        json.writeArrayStart("configuration")
        for ((key, value) in configuration) {
            json.writeValue(key, value, String::class.java)
        }
        json.writeArrayEnd()

        // Selector
        json.writeObjectStart("selector")
        json.writeStringArray(select.uris, "uris", skipEmpty = true)
        json.writeStringArray(select.files, "files", skipEmpty = true)
        json.writeStringArray(select.directories, "directories", skipEmpty = true)
        json.writeStringArray(select.packages, "packages", skipEmpty = true)
        json.writeStringArray(select.classes, "classes", skipEmpty = true)
        json.writeStringArray(select.methods, "methods", skipEmpty = true)
        json.writeStringArray(select.resources, "resources", skipEmpty = true)
        json.writeStringArray(select.classpathRoots, "classpathRoots", skipEmpty = true)
        json.writeObjectEnd()

        // Filter
        json.writeObjectStart("filter")
        json.writeValue("engines", filter.engines, IncludeExcludeList::class.java)
        json.writeValue("classNamePatterns", filter.classNamePatterns, IncludeExcludeList::class.java)
        json.writeValue("packages", filter.packages, IncludeExcludeList::class.java)
        json.writeValue("tags", filter.tags, IncludeExcludeList::class.java)
        json.writeObjectEnd()
    }

    override fun read(json: Json, value: JsonValue) {
        // Configuration
        value.get("configuration")?.forEach {
            configuration[it.name] = it.asString()
        }

        // Selector
        value.get("selector")?.let { selectorValue ->
            selectorValue.get("uris").putArrayStrings(select.uris)
            selectorValue.get("files").putArrayStrings(select.files)
            selectorValue.get("directories").putArrayStrings(select.directories)
            selectorValue.get("packages").putArrayStrings(select.packages)
            selectorValue.get("classes").putArrayStrings(select.classes)
            selectorValue.get("methods").putArrayStrings(select.methods)
            selectorValue.get("resources").putArrayStrings(select.resources)
            selectorValue.get("classpathRoots").putArrayStrings(select.classpathRoots)
        }

        // Filter
        value.get("filter")?.let { filterValue ->
            filterValue.get("engines")?.let { filter.engines.read(json, it) }
            filterValue.get("classNamePatterns")?.let { filter.classNamePatterns.read(json, it) }
            filterValue.get("packages")?.let { filter.packages.read(json, it) }
            filterValue.get("tags")?.let { filter.tags.read(json, it) }
        }
    }

    override fun toString(): String {
        return "TestParameters(configuration=$configuration, select=$select, filter=$filter)"
    }

    /**
     * Mutable collection coupling included and excluded patterns.
     */
    class IncludeExcludeList : JsonSerializable {
        val included:MutableList<String> = ArrayList()
        val excluded:MutableList<String> = ArrayList()

        fun include(item:String) {
            included.add(item)
        }

        fun include(vararg items:String) {
            included.addAll(items)
        }

        fun exclude(item:String) {
            excluded.add(item)
        }

        fun exclude(vararg items:String) {
            excluded.addAll(items)
        }

        fun isEmpty():Boolean = included.isEmpty() && excluded.isEmpty()

        override fun write(json: Json) {
            if (included.isNotEmpty()) {
                json.writeArrayStart("included")
                for (i in included) {
                    json.writeValue(i as Any, String::class.java)
                }
                json.writeArrayEnd()
            }
            if (excluded.isNotEmpty()) {
                json.writeArrayStart("excluded")
                for (e in excluded) {
                    json.writeValue(e as Any, String::class.java)
                }
                json.writeArrayEnd()
            }
        }

        override fun read(json: Json, value: JsonValue) {
            value.get("included")?.forEach {
                included.add(it.asString())
            }
            value.get("excluded")?.forEach {
                excluded.add(it.asString())
            }
        }

        override fun toString(): String {
            return "+$included -$excluded"
        }
    }
}