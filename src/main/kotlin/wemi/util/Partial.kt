package wemi.util

import com.esotericsoftware.jsonbeans.Json
import wemi.boot.MachineWritable

/**
 * Value that exists, but may be incomplete.
 * Some consumers don't care when the value is incomplete and can work with them. Some don't.
 */
class Partial<out T>(val value: T, val complete: Boolean) : MachineWritable {
    override fun writeMachine(json: Json) {
        json.writeObjectStart()
        json.writeValue("complete", complete, Boolean::class.java)
        json.writeValue("value", value)
        json.writeObjectEnd()
    }

    override fun toString(): String {
        return "$value (${if (complete) "complete" else "incomplete"})"
    }
}