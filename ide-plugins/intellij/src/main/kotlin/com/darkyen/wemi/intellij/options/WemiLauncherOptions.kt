package com.darkyen.wemi.intellij.options

import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.darkyen.wemi.intellij.ui.CommandArgumentEditor
import com.darkyen.wemi.intellij.ui.EnvironmentVariablesEditor
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.darkyen.wemi.intellij.ui.WemiJavaExecutableEditor
import com.darkyen.wemi.intellij.ui.WindowsShellExecutableEditor
import com.darkyen.wemi.intellij.util.BooleanXmlSerializer
import com.darkyen.wemi.intellij.util.ListOfStringXmlSerializer
import com.darkyen.wemi.intellij.util.MapStringStringXmlSerializer
import com.darkyen.wemi.intellij.util.StringXmlSerializer
import com.darkyen.wemi.intellij.util.XmlSerializable
import com.darkyen.wemi.intellij.util.findWindowsShellExe
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Paths

/** Base options for Wemi launching. */
abstract class WemiLauncherOptions : XmlSerializable() {

	var environmentVariables: Map<String, String> = emptyMap()
	var passParentEnvironmentVariables:Boolean = true

	var javaExecutable:String = ""
	var javaOptions:List<String> = emptyList()

	var windowsShellExecutable:String = ""

	fun getWindowsShellExecutable(project: Project?):String? {
		if (!SystemInfo.isWindows) {
			return ""
		}

		val exe = windowsShellExecutable
		if (exe.isNotBlank()) {
			val path = Paths.get(exe)
			if (Files.exists(path)) {
				return exe
			}
		}

		if (project != null) {
			val global = project.getService(WemiProjectService::class.java).options.getWindowsShellExecutable(null)
			if (global != null) {
				return global
			}
		}

		val detected = findWindowsShellExe()
		if (detected != null) {
			windowsShellExecutable = detected
			return detected
		}

		return null
	}

	init {
		register(WemiLauncherOptions::environmentVariables, MapStringStringXmlSerializer::class)
		register(WemiLauncherOptions::passParentEnvironmentVariables, BooleanXmlSerializer::class)
		register(WemiLauncherOptions::javaExecutable, StringXmlSerializer::class)
		register(WemiLauncherOptions::javaOptions, ListOfStringXmlSerializer::class)
		register(WemiLauncherOptions::windowsShellExecutable, StringXmlSerializer::class)
	}

	fun copyTo(o: WemiLauncherOptions) {
		o.environmentVariables = environmentVariables
		o.passParentEnvironmentVariables = passParentEnvironmentVariables
		o.javaExecutable = javaExecutable
		o.javaOptions = javaOptions
		o.windowsShellExecutable = windowsShellExecutable
	}

	open fun createUi(panel: PropertyEditorPanel) {
		panel.editRow(EnvironmentVariablesEditor(this::environmentVariables, this::passParentEnvironmentVariables))
		panel.gap(5)
		panel.editRow(WemiJavaExecutableEditor(this::javaExecutable))
		panel.editRow(CommandArgumentEditor(this::javaOptions))

		if (SystemInfo.isWindows && this is ProjectImportOptions) {
			panel.editRow(WindowsShellExecutableEditor(this::windowsShellExecutable))
		}
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is WemiLauncherOptions) return false

		if (environmentVariables != other.environmentVariables) return false
		if (passParentEnvironmentVariables != other.passParentEnvironmentVariables) return false
		if (javaExecutable != other.javaExecutable) return false
		if (javaOptions != other.javaOptions) return false
		if (windowsShellExecutable != other.windowsShellExecutable) return false

		return true
	}

	override fun hashCode(): Int {
		var result = environmentVariables.hashCode()
		result = 31 * result + passParentEnvironmentVariables.hashCode()
		result = 31 * result + javaExecutable.hashCode()
		result = 31 * result + javaOptions.hashCode()
		result = 31 * result + windowsShellExecutable.hashCode()
		return result
	}

}