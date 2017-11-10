package com.darkyen.wemi.intellij.settings

import com.intellij.openapi.externalSystem.util.ExternalSystemSettingsControl
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ui.configuration.JdkComboBox
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel

/**
 * Controller for [WemiProjectSettings]
 */
class WemiSystemSettingsControl(private val initialSettings: WemiSystemSettings) : ExternalSystemSettingsControl<WemiSystemSettings> {

    private lateinit var jdkSelectionModel:ProjectSdksModel
    private lateinit var jdkSelectionLabel:JBLabel
    private lateinit var jdkSelectionFromPATH:JBCheckBox
    private lateinit var jdkSelection:JdkComboBox


    override fun fillUi(canvas: PaintAwarePanel, indentLevel: Int) {
        jdkSelectionLabel = JBLabel("WEMI launcher JRE")
        jdkSelectionFromPATH = JBCheckBox("Use java from PATH")

        val model = ProjectSdksModel()
        jdkSelectionModel = model
        for (jdk in ProjectJdkTable.getInstance().allJdks) {
            model.addSdk(jdk)
        }

        jdkSelection = JdkComboBox(model)

        reset()

        jdkSelectionFromPATH.addChangeListener {
            jdkSelection.isEnabled = !jdkSelectionFromPATH.isSelected
        }

        canvas.add(jdkSelectionLabel, ExternalSystemUiUtil.getLabelConstraints(indentLevel))
        canvas.add(jdkSelectionFromPATH, ExternalSystemUiUtil.getFillLineConstraints(0))
        canvas.add(jdkSelection, ExternalSystemUiUtil.getFillLineConstraints(0))
    }

    private val selectedJre:String
        get() = if (jdkSelectionFromPATH.isSelected) {
            ""
        } else {
            jdkSelection.selectedJdk?.name ?: ""
        }

    override fun isModified(): Boolean {
        val jre = selectedJre
        return initialSettings.wemiLauncherJre != jre
    }

    override fun reset() {
        val selectedJdk = if (initialSettings.wemiLauncherJre.isEmpty())
            null
        else
            jdkSelectionModel.findSdk(initialSettings.wemiLauncherJre)

        if (selectedJdk == null) {
            jdkSelectionFromPATH.isSelected = true
            jdkSelection.isEnabled = false
        } else {
            jdkSelectionFromPATH.isSelected = false
            jdkSelection.isEnabled = true
            jdkSelection.selectedJdk = selectedJdk
        }
    }

    override fun apply(settings: WemiSystemSettings) {
        settings.wemiLauncherJre = selectedJre
    }


    override fun validate(projectSettings: WemiSystemSettings): Boolean {
        return true
    }

    override fun disposeUIResources() {
        ExternalSystemUiUtil.disposeUi(this)
    }

    override fun showUi(show: Boolean) {
        ExternalSystemUiUtil.showUi(this, show)
    }
}