package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.util.getWemiCompatibleSdk
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.options.ConfigurationException
import com.intellij.projectImport.ProjectImportWizardStep
import java.awt.BorderLayout
import java.lang.IllegalArgumentException
import javax.swing.JComponent
import javax.swing.JPanel

class ConfigureWemiProjectStep(context: WizardContext) : ProjectImportWizardStep(context) {

	private val optionsControl = ImportFromWemiControl()

	private val component:JPanel by lazy {
		val panel = JPanel(BorderLayout())
		panel.add(optionsControl.uiComponent)
		panel
	}

	override fun getComponent(): JComponent = component

	private var builderPrepared = false

	override fun updateStep() {
		if (!builderPrepared) {
			val context = wizardContext

			context.projectJdk = context.projectJdk ?: getWemiCompatibleSdk()
			optionsControl.reset(context, null)

			builderPrepared = true
		}
	}

	override fun updateDataModel() {
		val wizardContext = wizardContext
		optionsControl.apply(wizardContext)
	}

	@Throws(ConfigurationException::class)
	override fun validate(): Boolean {
		builder.ensureProjectIsDefined(this.wizardContext, optionsControl.getProjectImportOptions())
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