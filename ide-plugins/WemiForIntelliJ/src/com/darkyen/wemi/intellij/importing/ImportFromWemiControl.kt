package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.settings.*
import com.intellij.openapi.externalSystem.service.settings.AbstractImportFromExternalSystemControl
import com.intellij.openapi.project.ProjectManager

/**
 * Window that is shown when WEMI project is being imported.
 */
class ImportFromWemiControl
    : AbstractImportFromExternalSystemControl
        <WemiProjectSettings, WemiSettingsListener, WemiSystemSettings>(
            WemiProjectSystemId,
            WemiSystemSettings(ProjectManager.getInstance().defaultProject),
            WemiProjectSettings(),
            true) {

    /**
     * Called when user changes the path from which we will import the project
     */
    override fun onLinkedProjectPathChange(path: String) {
    }

    override fun createProjectSettingsControl(settings: WemiProjectSettings) = WemiProjectSettingsControl(settings)

    override fun createSystemSettingsControl(settings: WemiSystemSettings) = WemiSystemSettingsControl(settings)
}