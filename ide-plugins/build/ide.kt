@file:BuildDependencyPlugin("wemi-plugin-intellij")

import wemi.boot.BuildDependencyPlugin
import wemi.dependency.Jitpack
import wemi.util.FileSet
import wemi.util.plus
import wemiplugin.intellij.IntelliJPluginLayer
import wemiplugin.intellij.IntelliJIDE
import wemiplugin.intellij.IntelliJPluginDependency
import wemiplugin.intellij.IntelliJ.intellijIdeDependency
import wemiplugin.intellij.IntelliJ.intelliJPluginXmlFiles
import wemiplugin.intellij.IntelliJ.intellijPluginDependencies
import wemiplugin.intellij.IntelliJ.resolvedIntellijPluginDependencies

val distFolder by key<Path>("Distribution folder")
val ideArchiveDist by key<Path>("Build IDE plugin archive and put it into the distribution folder")

private val IdeIntelliJRoot = path("intellij")// TODO(jp): Expose the root!
val ideIntellij by project(IdeIntelliJRoot, Archetypes.JavaKotlinProject, IntelliJPluginLayer) {

	projectGroup set { "com.darkyen.wemi.intellij" }
	projectName set { "Wemi" }
	projectVersion set {
		// TODO(jp): Use system() to obtain it from parent project
		/* core/projectVersion */
		"0.15-SNAPSHOT"
	}

	distFolder set {
		// TODO(jp): Use system() to get this out of the parent project
		path("../build/dist")
	}

	repositories add { Jitpack }
	libraryDependencies add { dependency("com.github.EsotericSoftware", "jsonbeans", "0.9") }

	resources modify {
		// TODO(jp): Use system to get wemi/wemiLauncher
		val launcher = path("../build/dist/wemi")
		it + FileSet(launcher)
	}

	intellijPluginDependencies add { IntelliJPluginDependency.Bundled("com.intellij.java") }
	intellijPluginDependencies add { IntelliJPluginDependency.Bundled("org.jetbrains.kotlin") }

	intellijIdeDependency set { IntelliJIDE.External(version = "201.8743.12") }
	intelliJPluginXmlFiles add { LocatedPath(IdeIntelliJRoot / "src/main/plugin.xml") }

	ideArchiveDist set {
		val pluginArchive = intellijPluginArchive.get()
		Files.copy(pluginArchive, distFolder.get() / "WemiForIntelliJ.zip")
	}
}