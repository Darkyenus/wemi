package wemi.util

import com.darkyen.tproll.util.PrettyPrinter
import com.esotericsoftware.jsonbeans.JsonWriter
import wemi.dependency.DEFAULT_SCOPE

/**
 * A [LocatedPath] that is a part of classpath and carries a (Maven) scope information when that is.
 * Scope determines when exactly is the value a part of the classpath.
 * @see wemi.dependency.ScopeCompile (default)
 * @see wemi.dependency.ScopeRuntime
 * @see wemi.dependency.ScopeProvided
 * @see wemi.dependency.ScopeTest
 */
class ScopedLocatedPath(val value:LocatedPath, val scope:String = DEFAULT_SCOPE) : WithDescriptiveString, JsonWritable {
	operator fun component1():LocatedPath = value
	operator fun component2():String = scope

	override fun JsonWriter.write() {
		writeObject {
			// Copies LocatedPath write, to get nicer structure
			field("root", value.root)
			field("file", value.file)
			field("scope", scope)
		}
	}

	override fun toDescriptiveAnsiString(): String {
		val sb = StringBuilder(120)
		sb.format(Color.Blue)
		PrettyPrinter.append(sb, value)
		sb.format()
		if (scope != DEFAULT_SCOPE) {
			sb.format(Color.White).append(" $").format(Color.Black).append(scope).format()
		}
		return sb.toString()
	}

	override fun toString(): String {
		if (scope == DEFAULT_SCOPE) {
			return value.toString()
		} else {
			return "$value $$scope"
		}
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is ScopedLocatedPath) return false

		if (value != other.value) return false
		if (scope != other.scope) return false

		return true
	}

	override fun hashCode(): Int {
		var result = value.hashCode()
		result = 31 * result + scope.hashCode()
		return result
	}
}

/** Give [this] a Maven [scope]. */
fun LocatedPath.scoped(scope:String = DEFAULT_SCOPE): ScopedLocatedPath {
	return ScopedLocatedPath(this, scope)
}