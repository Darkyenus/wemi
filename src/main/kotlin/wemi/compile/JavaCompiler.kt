package wemi.compile

/** Extensions that a valid Java source file can have */
val JavaSourceFileExtensions = arrayOf("java")

/**
 * Flags used by the Java compiler.
 */
object JavaCompilerFlags {
    val customFlags = CompilerFlag<List<String>>("customFlags", "Custom flags to be parsed by the javac CLI", emptyList())
    val sourceVersion = CompilerFlag<String>("sourceVersion", "Version of the source files", "")
    val targetVersion = CompilerFlag<String>("targetVersion", "Version of the created class files", "")
}
