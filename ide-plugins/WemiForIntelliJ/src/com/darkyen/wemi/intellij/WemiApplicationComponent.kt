package com.darkyen.wemi.intellij

import com.darkyen.wemi.intellij.file.WemiLauncherFileType
import com.intellij.openapi.components.ApplicationComponent
import com.intellij.openapi.fileTypes.ExactFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager

/**
 * Application component for the Wemi plugin.
 *
 * Currently only for initialization.
 */
class WemiApplicationComponent : ApplicationComponent {

    override fun initComponent() {
        FileTypeManager.getInstance().associate(WemiLauncherFileType, ExactFileNameMatcher("wemi", false))
    }
}