package com.darkyen.wemi.intellij.file

import com.darkyen.wemi.intellij.WemiBuildFileExtensions
import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.intellij.openapi.vfs.VirtualFile

/**
 *
 */
fun VirtualFile?.isWemiLauncher():Boolean {
    if (this == null || this.isDirectory) {
        return false
    }
    return this.name == WemiLauncherFileName
}

fun VirtualFile?.isWemiScriptSource():Boolean {
    if (this == null || this.isDirectory || this.name.startsWith('.')) {
        return false
    }

    return WemiBuildFileExtensions.contains(this.extension?.toLowerCase() ?: "")
}

/**
 * Return true if this file path has any of the specified extensions.
 *
 * Not case sensitive.
 */
fun String.pathHasExtension(extensions: Iterable<String>): Boolean {
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