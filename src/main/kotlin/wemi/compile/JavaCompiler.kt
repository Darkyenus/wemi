package wemi.compile

/**
 *
 */
object JavaCompilerFlags {
    val customFlags = CompilerFlag<Collection<String>>("customFlags", "Custom flags to be parsed by the javac CLI")
}