package wemi.compile

import com.esotericsoftware.jsonbeans.JsonWriter
import wemi.collections.wSetOf
import wemi.util.JsonWritable
import wemi.util.writeValue

/**
 * Extensions that a valid Java source file can have.
 */
val JavaSourceFileExtensions = wSetOf("java")

/**
 * Flags used by the Java compiler.
 */
object JavaCompilerFlags {
    val customFlags = CompilerFlag<Collection<String>>("customFlags", "Custom flags to be parsed by the javac CLI")
    val sourceVersion = CompilerFlag<JavaVersion>("sourceVersion", "Version of the source files")
    val targetVersion = CompilerFlag<JavaVersion>("targetVersion", "Version of the created class files")
}

/**
 * Known Java versions.
 *
 * Used in [JavaCompilerFlags].
 */
enum class JavaVersion(val version: String) : JsonWritable {
    V1_5("1.5"),
    V1_6("1.6"),
    V1_7("1.7"),
    V1_8("1.8"),
    V1_9("1.9"),
    ;

    override fun JsonWriter.write() {
        writeValue(version, String::class.java)
    }

    override fun toString(): String = version
}