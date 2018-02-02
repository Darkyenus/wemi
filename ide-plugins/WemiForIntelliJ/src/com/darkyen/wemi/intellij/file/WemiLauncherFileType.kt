package com.darkyen.wemi.intellij.file

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import icons.WemiIcons
import javax.swing.Icon

/**
 * File type for Wemi launcher executable jar.
 */
object WemiLauncherFileType : FileType {

    override fun getDefaultExtension(): String {
        return ""
    }

    override fun getIcon(): Icon? = WemiIcons.LAUNCHER

    override fun getCharset(file: VirtualFile, content: ByteArray): String? {
        return null
    }

    override fun getName(): String {
        return "Wemi Launcher"
    }

    override fun getDescription(): String {
        return "Wemi Launcher executable jar"
    }

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true
}