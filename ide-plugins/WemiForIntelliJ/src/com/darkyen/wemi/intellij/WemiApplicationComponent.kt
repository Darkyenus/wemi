package com.darkyen.wemi.intellij

import com.darkyen.wemi.intellij.file.WemiLauncherFileType
import com.intellij.openapi.components.ApplicationComponent
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants.USE_IN_PROCESS_COMMUNICATION_REGISTRY_KEY_SUFFIX
import com.intellij.openapi.fileTypes.ExactFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.registry.Registry

/**
 * Application component for the Wemi plugin.
 *
 * Currently only for initialization.
 */
class WemiApplicationComponent : ApplicationComponent {

    override fun initComponent() {
        FileTypeManager.getInstance().associate(WemiLauncherFileType, ExactFileNameMatcher(WemiLauncherFileName, false))

        // Do not launch our WemiProjectResolver and WemiTaskManager in external process,
        // because it just adds delays and is messy
        Registry.get("${WemiProjectSystemId.id}$USE_IN_PROCESS_COMMUNICATION_REGISTRY_KEY_SUFFIX").setValue(true)
    }
}