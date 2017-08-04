package wemi

import configuration
import wemi.compile.KotlinCompiler
import java.nio.charset.StandardCharsets
import java.util.*
import javax.tools.ToolProvider

/**
 *
 */
object Configurations {
    val javaCompilation by configuration("Configuration used when compiling Java sources") {
        Keys.sourceExtensions set { KeyDefaults.SourceExtensionsJavaList }
        Keys.javaCompiler set {
            val compiler = ToolProvider.getSystemJavaCompiler()
            val fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)
            Pair(compiler, fileManager)
        }
        Keys.compilerOptions set { KeyDefaults.JavaCompilerOptionsList }
    }

    val kotlinCompilation by configuration("Configuration used when compiling Kotlin sources") {
        Keys.sourceExtensions set { KeyDefaults.SourceExtensionsKotlinList }
        Keys.kotlinCompiler set {
            KotlinCompiler
        }
        Keys.compilerOptions set { KeyDefaults.KotlinCompilerOptionsList }
    }
}