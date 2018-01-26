package wemi.compile.impl

import com.intellij.core.CoreFileTypeRegistry
import com.intellij.mock.MockProject
import com.intellij.openapi.fileTypes.FileTypeRegistry
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import wemi.boot.WemiBuildFileExtensions

/**
 * Part of Kotlin Compiler 1.2.20, but used in all versions
 *
 * This class injects .wemi file extension into the environment of Kotlin compiler.
 *
 * This needs to be done in compiler plugin, because we need to access [FileTypeRegistry.getInstance] which fails
 * before the compiler initializes.
 */
class RegisterWemiFilesAsKotlinFilesInCompiler : ComponentRegistrar {

    override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
        val registry = FileTypeRegistry.getInstance() as CoreFileTypeRegistry
        // This registers the KotlinFileType multiple times, but the official code does that as well
        registry.registerFileType(KotlinFileType.INSTANCE, WemiBuildFileExtensions.joinToString(";"))
    }
}