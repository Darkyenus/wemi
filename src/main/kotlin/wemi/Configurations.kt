package wemi

import configuration
import wemi.KeyDefaults.SourceBaseScopeTest
import wemi.KeyDefaults.SourceRootsJavaKotlin
import wemi.compile.*
import javax.tools.ToolProvider

/**
 * All default configurations
 */
object Configurations {

    //region Stage configurations
    val compiling by configuration("Configuration used when compiling") {}
    
    val running by configuration("Configuration used when running, sources are resources") {}
    //endregion

    //region Testing configuration
    val testing by configuration("Used when testing") {
        Keys.sourceBase set KeyDefaults.SourceBaseScopeTest
    }
    //endregion

    val compilingJava by configuration("Configuration used when compiling Java sources", compiling) {
        Keys.sourceRoots set KeyDefaults.SourceRootsJavaKotlin
        Keys.sourceExtensions set { KeyDefaults.SourceExtensionsJavaList }
        Keys.javaCompiler set { ToolProvider.getSystemJavaCompiler() }
        Keys.compilerOptions[JavaCompilerFlags.customFlags] += "-g"
    }

    val compilingKotlin by configuration("Configuration used when compiling Kotlin sources", compiling) {
        Keys.sourceRoots set KeyDefaults.SourceRootsJavaKotlin
        Keys.sourceExtensions set { KeyDefaults.SourceExtensionsKotlinList }
        Keys.kotlinCompiler set { kotlinCompiler(WemiKotlinVersion) }
    }
}