package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.externalSystem.service.settings.AbstractSettingsControl
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.project.Project
import com.intellij.projectImport.ProjectFormatPanel
import java.awt.GridBagLayout
import javax.swing.JLabel

/**
 * Window that is shown when WEMI project is being imported.
 */
class ImportFromWemiControl : AbstractSettingsControl() {

    private val options = ProjectImportOptions()
    private val optionsEditor = PropertyEditorPanel().also { panel ->
        options.createUi(panel)
    }

    val uiComponent = PaintAwarePanel(GridBagLayout())

    val projectFormatPanel = ProjectFormatPanel()

    init {
        uiComponent.add(optionsEditor, ExternalSystemUiUtil.getFillLineConstraints(0))

        val myProjectFormatLabel = JLabel(ExternalSystemBundle.message("settings.label.project.format"))
        uiComponent.add(myProjectFormatLabel, ExternalSystemUiUtil.getLabelConstraints(0))
        uiComponent.add(projectFormatPanel.storageFormatComboBox, ExternalSystemUiUtil.getFillLineConstraints(0))
        ExternalSystemUiUtil.fillBottom(uiComponent)
    }

    public override fun reset(wizardContext: WizardContext?, project: Project?) {
        super.reset(wizardContext, project)
        this.project?.getService(WemiProjectService::class.java)?.state?.options?.copyTo(options)
        optionsEditor.loadFromProperties()
    }

    fun apply() {
        this.project?.getService(WemiProjectService::class.java)?.state?.let { projectState ->
            optionsEditor.saveToProperties()
            options.copyTo(projectState.options)
        }
    }

    fun getProjectImportOptions():ProjectImportOptions {
        optionsEditor.saveToProperties()
        return options
    }
}