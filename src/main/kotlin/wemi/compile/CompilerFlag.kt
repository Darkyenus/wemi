package wemi.compile

import com.esotericsoftware.jsonbeans.Json
import org.slf4j.LoggerFactory
import wemi.boot.MachineWritable

/**
 * Key for compiler flags. Each compiler may have different flags and may need different code to apply them.
 * This allows for unified abstraction. Do not create instances of [CompilerFlag], use the ones provided by the compiler.
 *
 * @see CompilerFlags
 */
@Suppress("unused")//Type is technically unused, but helps when compile-time type-checking
class CompilerFlag<Type>(val name: String, val description: String) : MachineWritable {
    override fun writeMachine(json: Json) {
        json.writeValue(name as Any, String::class.java)
    }

    override fun toString(): String {
        return "$name - $description"
    }
}

private val LOG = LoggerFactory.getLogger("CompilerFlag")

/**
 * Mutable container that holds bindings of [CompilerFlag] to their values.
 *
 * It also tracks which flags were already used, for later examination and diagnostics.
 */
class CompilerFlags : MachineWritable {
    private val map = HashMap<CompilerFlag<*>, Any?>()
    private val used = HashSet<CompilerFlag<*>>()

    /** Set the value associated with given flag */
    operator fun <T> set(flag: CompilerFlag<T>, value: T) {
        map[flag] = value
    }

    /** Get the value associated with given flag */
    operator fun <T> get(flag: CompilerFlag<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return map[flag] as T?
    }


    /** Used by the compiler to get the value associated with given flag and mark it as used for [forEachUnused].
     * @param default value used if the flag is not set */
    fun <T> useDefault(flag: CompilerFlag<T>, default: T): T {
        used.add(flag)
        @Suppress("UNCHECKED_CAST")
        return map.getOrDefault(flag, default) as T
    }

    /** Used by the compiler to get the value associated with given flag and mark it as used for [forEachUnused]. */
    fun <T> useOrNull(flag: CompilerFlag<T>): T? {
        used.add(flag)
        @Suppress("UNCHECKED_CAST")
        return map.getOrDefault(flag, null) as T?
    }

    /** Used by the compiler to get the value associated with given flag and mark it as used for [forEachUnused].
     * @param action called if flag is set */
    fun <T> use(flag: CompilerFlag<T>, action: (T) -> Unit) {
        used.add(flag)
        if (map.containsKey(flag)) {
            @Suppress("UNCHECKED_CAST")
            action(map[flag] as T)
        }
    }

    /** Iterate through all set but unused keys (used flag is set when querying with [use] method). */
    private fun forEachUnused(action: (CompilerFlag<*>) -> Unit) {
        for ((key, _) in map) {
            if (!used.contains(key)) {
                action(key)
            }
        }
    }

    /**
     * Log a warning for all unused flags, if any.
     *
     * This can be useful when debugging if all flags are effective or not.
     */
    internal fun warnAboutUnusedFlags(compilerName: String) {
        val sb = StringBuilder()
        forEachUnused {
            if (sb.isNotEmpty()) {
                sb.append(", ")
            }
            sb.append(it.name)
        }
        if (sb.isNotEmpty()) {
            LOG.warn("Following flags were not used by the {}: {}", compilerName, sb)
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
    override fun writeMachine(json: Json) {
        json.writeValue(map)
    }
}