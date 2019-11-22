package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.intellij.openapi.options.SettingsEditor
import javax.swing.JComponent

/** Editor for WemiTaskConfiguration */
class WemiTaskConfigurationEditor : SettingsEditor<WemiTaskConfiguration>() {

    private val options = WemiTaskConfiguration.RunOptions()
    private val propertyEditor = PropertyEditorPanel()

    init {
        options.createUi(propertyEditor)
    }

    override fun resetEditorFrom(s: WemiTaskConfiguration) {
        s.options.copyTo(options)
        propertyEditor.loadFromProperties()
    }

    override fun applyEditorTo(s: WemiTaskConfiguration) {
        propertyEditor.saveToProperties()
        options.copyTo(s.options)
    }

    override fun createEditor(): JComponent = propertyEditor
}
