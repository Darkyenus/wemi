package wemi.compile

/** Extensions that a valid Java source file can have */
val JavaSourceFileExtensions = arrayOf("java")

/**
 * Flags used by the Java compiler.
 */
object JavaCompilerFlags {
    val customFlags = CompilerFlag<List<String>>("javacCustomFlags", "Custom flags to be parsed by the javac CLI", emptyList())
    val sourceVersion = CompilerFlag<String>("javacSourceVersion", "Version of the source files", "")
    val targetVersion = CompilerFlag<String>("javacTargetVersion", "Version of the created class files", "")
    val encoding = CompilerFlag<String>("encoding", "Charset encoding of source files", "")
}
