package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings
import com.intellij.openapi.project.Project

/**
 *
 */
@State(name = "WemiLocalSettings", storages = [(Storage(StoragePathMacros.WORKSPACE_FILE))])
class WemiLocalSettings(project: Project)
    : AbstractExternalSystemLocalSettings<AbstractExternalSystemLocalSettings.State>(WemiProjectSystemId, project),
        PersistentStateComponent<AbstractExternalSystemLocalSettings.State> {

    companion object {
        fun getInstance(project: Project): WemiLocalSettings = ServiceManager.getService<WemiLocalSettings>(project, WemiLocalSettings::class.java)
    }
}