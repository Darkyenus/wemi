package com.darkyen.wemi.intellij.options

import com.darkyen.wemi.intellij.ui.BooleanPropertyEditor
import com.darkyen.wemi.intellij.ui.CommandArgumentEditor
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel

/** Options for project importing */
class ProjectImportOptions : WemiLauncherOptions() {

	var prefixConfigurations:List<String> = emptyList()
	var downloadSources:Boolean = true
	var downloadDocumentation:Boolean = true

	fun copyTo(o: ProjectImportOptions) {
		copyTo(o as WemiLauncherOptions)
		o.prefixConfigurations = prefixConfigurations
		o.downloadSources = downloadSources
		o.downloadDocumentation = downloadDocumentation
	}

	override fun createUi(panel: PropertyEditorPanel) {
		super.createUi(panel)

		panel.edit(CommandArgumentEditor(this::prefixConfigurations, "Prefix configurations"))// TODO(jp): Better editor
		panel.edit(BooleanPropertyEditor(this::downloadSources, "Download sources"))
		panel.edit(BooleanPropertyEditor(this::downloadDocumentation, "Download documentation"))
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is ProjectImportOptions) return false
		if (!super.equals(other)) return false

		if (prefixConfigurations != other.prefixConfigurations) return false
		if (downloadSources != other.downloadSources) return false
		if (downloadDocumentation != other.downloadDocumentation) return false

		return true
	}

	override fun hashCode(): Int {
		var result = super.hashCode()
		result = 31 * result + prefixConfigurations.hashCode()
		result = 31 * result + downloadSources.hashCode()
		result = 31 * result + downloadDocumentation.hashCode()
		return result
	}

}