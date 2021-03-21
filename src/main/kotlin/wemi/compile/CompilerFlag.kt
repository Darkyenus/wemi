package wemi.compile

import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.util.JsonWritable
import wemi.util.writeMap
import wemi.util.writeValue

/**
 * Key for compiler flags. Each compiler may have different flags and may need different code to apply them.
 * This allows for unified abstraction. Do not create instances of [CompilerFlag], use the ones provided by the compiler.
 *
 * @see CompilerFlags
 * @param default used only as a base for modifications
 */
class CompilerFlag<Type>(val name: String, val description: String, val default:Type) : JsonWritable {

    override fun JsonWriter.write() {
        writeValue(name, String::class.java)
    }

    override fun toString(): String {
        return "$name - $description"
    }
}

private val LOG = LoggerFactory.getLogger("CompilerFlag")

/** A mutable container that holds bindings of [CompilerFlag] to their values.
 * The standard API for setting keys with [CompilerFlags] value is:
 * `compilerOptions[SomeOption ] = { previousValue -> "value" }`
 * for example:
 * `compilerOptions[JavaCompilerFlags.customFlags] = { it + "-Xlint" }`.
 */
class CompilerFlags : JsonWritable {
    private val map = HashMap<CompilerFlag<*>, Any?>()

    /** Set the value associated with given flag */
    operator fun <T> set(flag: CompilerFlag<T>, value: T) {
        map[flag] = value
    }

    /** Get the value associated with given flag */
    fun <T> getOrDefault(flag: CompilerFlag<T>): T {
        @Suppress("UNCHECKED_CAST")
        return map.getOrDefault(flag, flag.default) as T
    }

    /** Get the value associated with given flag */
    fun <T> getOrNull(flag: CompilerFlag<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return map[flag] as T?
    }

    fun <T> unset(flag: CompilerFlag<T>) {
        map.remove(flag)
    }

    /** @param action called if flag is set, if it is set */
    fun <T> use(flag: CompilerFlag<T>, action: (T) -> Unit) {
        if (map.containsKey(flag)) {
            @Suppress("UNCHECKED_CAST")
            action(map[flag] as T)
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("{")
        var first = true
        for ((k, v) in map) {
            if (first) {
                first = false
            } else {
                sb.append(", ")
            }
            sb.append(k.name)
            sb.append(" -> ")
            sb.append(v)
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * Written out as a [Map].
     */
    override fun JsonWriter.write() {
        writeMap(CompilerFlag::class.java, null, map)
    }
}