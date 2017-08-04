package wemi

import configuration
import wemi.compile.KotlinCompiler
import javax.tools.ToolProvider

/**
 *
 */
object Configurations {
    
    val compiling by configuration("Configuration used when compiling") {}
    
    val running by configuration("Configuration used when compiling") {}
    
    val compilingJava by configuration("Configuration used when compiling Java sources", compiling) {
        Keys.sourceExtensions set { KeyDefaults.SourceExtensionsJavaList }
        Keys.javaCompiler set { ToolProvider.getSystemJavaCompiler() }
        Keys.compilerOptions set { KeyDefaults.JavaCompilerOptionsList }
    }

    val compilingKotlin by configuration("Configuration used when compiling Kotlin sources", compiling) {
        Keys.sourceExtensions set { KeyDefaults.SourceExtensionsKotlinList }
        Keys.kotlinCompiler set {
            KotlinCompiler
        }
        Keys.compilerOptions set { KeyDefaults.KotlinCompilerOptionsList }
    }
}