package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.darkyen.wemi.intellij.importing.defaultWemiRootPathFor
import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.util.MapStringStringXmlSerializer
import com.darkyen.wemi.intellij.util.StringXmlSerializer
import com.darkyen.wemi.intellij.util.XmlSerializable
import com.darkyen.wemi.intellij.util.xmlSerializableSerializer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Paths

/** Stuff saved per IDE project */
@Service
@State(name = "wemi.WemiProjectService")
class WemiProjectService(project: Project) : XmlSerializable() {

	private var wemiLauncherPath:String = defaultWemiRootPathFor(project)?.resolve(WemiLauncherFileName)?.toString() ?: ""
	var options = ProjectImportOptions()
	var tasks = emptyMap<String, String>()

	private var wemiLauncherCache:WemiLauncher? = if (wemiLauncherPath.isBlank()) {
		null
	} else WemiLauncher(Paths.get(wemiLauncherPath))

	var wemiLauncher:WemiLauncher?
		get() {
			val cache = wemiLauncherCache ?: return null
			if (Files.isRegularFile(cache.file)) {
				return cache
			} else {
				return null
			}
		}
		set(value) {
			if (value == null) {
				wemiLauncherPath = value ?: ""
			} else {
				wemiLauncherPath = value.file.toAbsolutePath().normalize().toString()
			}
			wemiLauncherCache = value
		}

	init {
		register(WemiProjectService::wemiLauncherPath, StringXmlSerializer::class)
		register(WemiProjectService::options, xmlSerializableSerializer<ProjectImportOptions>())
		register(WemiProjectService::tasks, MapStringStringXmlSerializer::class)
	}
}