package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.util.MapStringStringXmlSerializer
import com.darkyen.wemi.intellij.util.XmlSerializable
import com.darkyen.wemi.intellij.util.xmlSerializableSerializer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State

/** Stuff saved per IDE project */
@Service
@State(name = "wemi.WemiProjectService")
class WemiProjectService : XmlSerializable() {

	var options = ProjectImportOptions()
	var tasks = emptyMap<String, String>()

	init {
		register(WemiProjectService::options, xmlSerializableSerializer<ProjectImportOptions>())
		register(WemiProjectService::tasks, MapStringStringXmlSerializer::class)
	}
}