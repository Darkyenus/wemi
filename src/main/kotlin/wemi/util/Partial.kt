package wemi.util

import com.esotericsoftware.jsonbeans.JsonWriter

/**
 * Value that exists, but may be incomplete.
 * Some consumers don't care when the value is incomplete and can work with them. Some don't.
 *
 * @param value that may be partial
 * @param complete true if the [value] is not partial
 */
class Partial<out T>(val value: T, val complete: Boolean) : JsonWritable {

    operator fun component1() = value
    operator fun component2() = complete

    override fun JsonWriter.write() {
        writeObject {
            field("complete", complete)
            name("value").writeValue(value, null)
        }
    }

    override fun toString(): String {
        return "$value (${if (complete) "complete" else "incomplete"})"
    }
}