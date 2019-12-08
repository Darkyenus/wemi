package com.darkyen.wemi.intellij.projectImport

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.options.ConfigurationException
import com.intellij.projectImport.ProjectImportWizardStep
import java.awt.BorderLayout
import java.lang.IllegalArgumentException
import javax.swing.JComponent
import javax.swing.JPanel

class ConfigureWemiProjectStep(context: WizardContext) : ProjectImportWizardStep(context) {

	private val control: ImportFromWemiControl by lazy { builder.control }

	private val component:JPanel by lazy {
		val panel = JPanel(BorderLayout())
		panel.add(control.uiComponent)
		panel
	}

	override fun getComponent(): JComponent = component

	private var builderPrepared = false

	override fun updateStep() {
		if (!builderPrepared) {
			builder.prepare(wizardContext)
			builderPrepared = true
		}
	}

	override fun updateDataModel() {}

	@Throws(ConfigurationException::class)
	override fun validate(): Boolean {
		val wizardContext = wizardContext
		control.apply()
		control.projectFormatPanel.updateData(wizardContext)
		builder.ensureProjectIsDefined(wizardContext)
		return true
	}

	override fun getBuilder(): ImportFromWemiBuilder {
		var projectBuilder = wizardContext.projectBuilder
		if (projectBuilder == null) {
			projectBuilder = ImportFromWemiBuilder()
			wizardContext.projectBuilder = projectBuilder
			return projectBuilder
		} else if (projectBuilder !is ImportFromWemiBuilder) {
			throw IllegalArgumentException("Invalid project builder: got $projectBuilder, expected ImportFromWemiBuilder")
		}
		return projectBuilder
	}
}