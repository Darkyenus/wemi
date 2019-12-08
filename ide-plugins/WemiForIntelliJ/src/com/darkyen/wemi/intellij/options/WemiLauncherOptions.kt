package com.darkyen.wemi.intellij.options

import com.darkyen.wemi.intellij.ui.CommandArgumentEditor
import com.darkyen.wemi.intellij.ui.EnvironmentVariablesEditor
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.darkyen.wemi.intellij.ui.WemiJavaExecutableEditor
import com.darkyen.wemi.intellij.util.BooleanXmlSerializer
import com.darkyen.wemi.intellij.util.ListOfStringXmlSerializer
import com.darkyen.wemi.intellij.util.MapStringStringXmlSerializer
import com.darkyen.wemi.intellij.util.StringXmlSerializer
import com.darkyen.wemi.intellij.util.XmlSerializable

/** Base options for Wemi launching. */
abstract class WemiLauncherOptions : XmlSerializable() {

	var environmentVariables: Map<String, String> = emptyMap()
	var passParentEnvironmentVariables:Boolean = true

	var javaExecutable:String = ""
	var javaOptions:List<String> = emptyList()

	init {
		register(WemiLauncherOptions::environmentVariables, MapStringStringXmlSerializer::class)
		register(WemiLauncherOptions::passParentEnvironmentVariables, BooleanXmlSerializer::class)
		register(WemiLauncherOptions::javaExecutable, StringXmlSerializer::class)
		register(WemiLauncherOptions::javaOptions, ListOfStringXmlSerializer::class)
	}

	fun copyTo(o: WemiLauncherOptions) {
		o.environmentVariables = environmentVariables
		o.passParentEnvironmentVariables = passParentEnvironmentVariables
		o.javaExecutable = javaExecutable
		o.javaOptions = javaOptions
	}

	open fun createUi(panel: PropertyEditorPanel) {
		panel.edit(EnvironmentVariablesEditor(this::environmentVariables, this::passParentEnvironmentVariables))
		panel.gap(5)
		panel.edit(WemiJavaExecutableEditor(this::javaExecutable))
		panel.edit(CommandArgumentEditor(this::javaOptions))
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is WemiLauncherOptions) return false

		if (environmentVariables != other.environmentVariables) return false
		if (passParentEnvironmentVariables != other.passParentEnvironmentVariables) return false
		if (javaExecutable != other.javaExecutable) return false
		if (javaOptions != other.javaOptions) return false

		return true
	}

	override fun hashCode(): Int {
		var result = environmentVariables.hashCode()
		result = 31 * result + passParentEnvironmentVariables.hashCode()
		result = 31 * result + javaExecutable.hashCode()
		result = 31 * result + javaOptions.hashCode()
		return result
	}

}