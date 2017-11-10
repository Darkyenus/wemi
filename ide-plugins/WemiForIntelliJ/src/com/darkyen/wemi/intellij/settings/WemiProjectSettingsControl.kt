package com.darkyen.wemi.intellij.settings

import com.intellij.openapi.externalSystem.service.settings.AbstractExternalProjectSettingsControl
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField

/**
 * Controller for [WemiProjectSettings]
 */
class WemiProjectSettingsControl(val settings: WemiProjectSettings) : AbstractExternalProjectSettingsControl<WemiProjectSettings>(settings) {

    private lateinit var prefixConfigurationsLabel: JBLabel
    private lateinit var prefixConfigurationsField: JBTextField
    private lateinit var downloadDocsBox: JBCheckBox
    private lateinit var downloadSourcesBox: JBCheckBox

    override fun fillExtraControls(content: PaintAwarePanel, indentLevel: Int) {
        prefixConfigurationsLabel = JBLabel("Prefix configurations:")
        prefixConfigurationsField = JBTextField()
        prefixConfigurationsLabel.toolTipText = "Configuration names separated by a colon"
        downloadDocsBox = JBCheckBox("Download documentation for dependencies")
        downloadSourcesBox = JBCheckBox("Download sources for dependencies")

        content.add(prefixConfigurationsLabel, ExternalSystemUiUtil.getLabelConstraints(indentLevel))
        content.add(prefixConfigurationsField, ExternalSystemUiUtil.getFillLineConstraints(0))
        content.add(downloadDocsBox, ExternalSystemUiUtil.getFillLineConstraints(indentLevel))
        content.add(downloadSourcesBox, ExternalSystemUiUtil.getFillLineConstraints(indentLevel))
    }

    override fun applyExtraSettings(settings: WemiProjectSettings) {
        settings.downloadDocs = downloadDocsBox.isSelected
        settings.downloadSources = downloadSourcesBox.isSelected
        settings.prefixConfigurations = prefixConfigurationsField.text
    }

    override fun updateInitialExtraSettings() {
        applyExtraSettings(initialSettings)
    }

    override fun isExtraSettingModified(): Boolean {
        return initialSettings.downloadDocs != downloadDocsBox.isSelected
                || initialSettings.downloadSources != downloadSourcesBox.isSelected
                || initialSettings.prefixConfigurations != prefixConfigurationsField.text
    }

    override fun resetExtraSettings(isDefaultModuleCreation: Boolean) {
        downloadDocsBox.isSelected = initialSettings.downloadDocs
        downloadSourcesBox.isSelected = initialSettings.downloadSources
        prefixConfigurationsField.text = initialSettings.prefixConfigurations
    }

    override fun validate(projectSettings: WemiProjectSettings): Boolean {
        if (prefixConfigurationsField.text.contains(WHITESPACE_REGEX)) {
            val ex = ConfigurationException("Prefix configurations field may not contain whitespace")
            ex.setQuickFix {
                prefixConfigurationsField.text = prefixConfigurationsField.text.replace(WHITESPACE_REGEX, "")
            }
            throw ex
        }
        return true
    }

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}