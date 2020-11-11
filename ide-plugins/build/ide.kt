@file:BuildDependencyPlugin("wemi-plugin-intellij")
// TODO(jp): version.kt is currently added via symlink, add a Import directive to do it properly

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
import wemiplugin.intellij.IntelliJ.intellijPluginArchive
import wemi.key

val ideIntellij by project(path("intellij"), Archetypes.JavaKotlinProject, IntelliJPluginLayer) {

	projectGroup set { "com.darkyen.wemi.intellij" }
	projectName set { "Wemi" }
	projectVersion set { gatherProjectVersion() }

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
	intelliJPluginXmlFiles add { LocatedPath(Keys.projectRoot.get() / "src/main/plugin.xml") }
}