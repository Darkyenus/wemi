package com.darkyen.wemi.intellij

import com.darkyen.wemi.intellij.file.WemiLauncherFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseComponent
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants.USE_IN_PROCESS_COMMUNICATION_REGISTRY_KEY_SUFFIX
import com.intellij.openapi.fileTypes.ExactFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.registry.Registry

/**
 * Application component for the Wemi plugin.
 *
 * Currently only for initialization.
 */
class WemiApplicationComponent : BaseComponent {
    // TODO(jp): Do what https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/components/ApplicationComponent.java says

    override fun initComponent() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                FileTypeManager.getInstance().associate(WemiLauncherFileType, ExactFileNameMatcher(WemiLauncherFileName, false))

                // Do not launch our WemiProjectResolver and WemiTaskManager in external process,
                // because it just adds delays and is messy
                Registry.get("${WemiProjectSystemId.id}$USE_IN_PROCESS_COMMUNICATION_REGISTRY_KEY_SUFFIX").setValue(true)
            }
        }
    }

    override fun getComponentName(): String {
        return "WemiApplicationComponent"
    }

    override fun disposeComponent() {}
}