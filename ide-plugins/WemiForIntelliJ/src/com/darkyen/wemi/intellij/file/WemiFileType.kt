package com.darkyen.wemi.intellij.file

import com.darkyen.wemi.intellij.WemiBuildFileExtensions
import com.intellij.openapi.fileTypes.LanguageFileType
import icons.WemiIcons
import org.jetbrains.kotlin.idea.KotlinLanguage
import javax.swing.Icon

/**
 * File type of Wemi files
 */
object WemiFileType : LanguageFileType(KotlinLanguage.INSTANCE) {

    override fun getIcon(): Icon? {
        return WemiIcons.SCRIPT
    }

    override fun getName(): String {
        return "Wemi"
    }

    override fun getDefaultExtension(): String {
        return WemiBuildFileExtensions[0]
    }

    override fun getDescription(): String {
        return "Wemi Build Script"
    }
}