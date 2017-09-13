package wemi.compile

val JavaSourceFileExtensions = listOf("java")

/**
 *
 */
object JavaCompilerFlags {
    val customFlags = CompilerFlag<Collection<String>>("customFlags", "Custom flags to be parsed by the javac CLI")
}