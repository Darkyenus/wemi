package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/** Allows to configure Wemi settings per project, presents the UI in settings. */
class WemiProjectServiceConfigurable(private val project: Project) : SearchableConfigurable, DumbAware {

    override fun getId() = "com.darkyen.wemi.configurable-settings"

    override fun getDisplayName(): String = "Wemi"

    private val effectiveProjectSettings:ProjectImportOptions?
        get() = project.getService(WemiProjectService::class.java)?.options

    private val editorProjectSettings = ProjectImportOptions().also { editOptions ->
        project.getService(WemiProjectService::class.java)?.updateWindowsShellExe()
        effectiveProjectSettings?.copyTo(editOptions)
    }

    private val editPanel = PropertyEditorPanel().also { panel ->
        editorProjectSettings.createUi(panel)
        panel.loadFromProperties()
    }

    override fun createComponent(): JComponent? = editPanel

    override fun isModified(): Boolean {
        editPanel.saveToProperties()
        return editorProjectSettings != effectiveProjectSettings
    }

    override fun apply() {
        editPanel.saveToProperties()
        editorProjectSettings.copyTo(effectiveProjectSettings ?: return)
    }
}