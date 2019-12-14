package com.darkyen.wemi.intellij.file

import com.darkyen.wemi.intellij.WemiBuildDirectoryName
import com.darkyen.wemi.intellij.WemiBuildFileExtensions
import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

internal fun VirtualFile?.isWemiLauncher():Boolean {
    if (this == null || this.isDirectory || !this.exists()) {
        return false
    }
    return this.name == WemiLauncherFileName
}

internal fun VirtualFile?.isWemiScriptSource(deepCheck:Boolean = false):Boolean {
    if (this == null || this.isDirectory || this.name.startsWith('.')) {
        return false
    }

    if (!WemiBuildFileExtensions.contains(this.extension?.toLowerCase() ?: "")) {
        return false
    }

    if (deepCheck) {
        val buildDirectory = this.parent ?: return false
        if (!buildDirectory.name.equals(WemiBuildDirectoryName, ignoreCase = true)) {
            return false
        }
        val projectDirectory = buildDirectory.parent ?: return false
        val wemiLauncher = projectDirectory.findChild(WemiLauncherFileName) ?: return false
        return wemiLauncher.isWemiLauncher()
    }

    return true
}

internal fun Path?.isWemiLauncher():Boolean {
    if (this == null || Files.isDirectory(this) || !Files.exists(this)) {
        return false
    }
    return this.fileName.toString() == WemiLauncherFileName
}

/**
 * Return true if this file path has any of the specified extensions.
 *
 * Not case sensitive.
 */
internal fun String.pathHasExtension(extensions: Iterable<String>): Boolean {
    val name = this
    val length = name.length
    for (extension in extensions) {
        if (length >= extension.length + 1
                && name.endsWith(extension, ignoreCase = true)
                && name[length - extension.length - 1] == '.') {
            return true
        }
    }
    return false
}