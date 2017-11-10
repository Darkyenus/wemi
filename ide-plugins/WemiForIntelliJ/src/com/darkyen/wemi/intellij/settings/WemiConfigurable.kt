package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.externalSystem.service.settings.AbstractExternalSystemConfigurable
import com.intellij.openapi.project.Project

/**
 * TODO When is this used?
 */
class WemiConfigurable(project: Project) : AbstractExternalSystemConfigurable<WemiProjectSettings, WemiSettingsListener, WemiSystemSettings>(project, WemiProjectSystemId) {

    override fun createProjectSettingsControl(settings: WemiProjectSettings) = WemiProjectSettingsControl(settings)

    override fun newProjectSettings() = WemiProjectSettings()

    override fun createSystemSettingsControl(settings: WemiSystemSettings) = WemiSystemSettingsControl(settings)

    override fun getHelpTopic() = null

    override fun getId() = "com.darkyen.wemi.configurable-settings"
}