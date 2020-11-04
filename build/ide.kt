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

private val IdeIntelliJRoot = path("ide-plugins/intellij")// TODO(jp): Expose the root!
val ideIntelliJ by project(IdeIntelliJRoot, Archetypes.JavaKotlinProject, IntelliJPluginLayer) {

	projectGroup set { "com.darkyen.wemi.intellij" }
	projectName set { "Wemi" }
	projectVersion set { using(core) { projectVersion.get() } }

	repositories add { Jitpack }
	libraryDependencies add { dependency("com.github.EsotericSoftware", "jsonbeans", "0.9") }

	resources modify { using(wemi) { it + FileSet(wemiLauncher.get()) } }

	intellijPluginDependencies add { IntelliJPluginDependency.Bundled("com.intellij.java") }
	intellijPluginDependencies add { IntelliJPluginDependency.Bundled("org.jetbrains.kotlin") }

	intellijIdeDependency set { IntelliJIDE.External(version = "201.8743.12") }
	intelliJPluginXmlFiles add { LocatedPath(IdeIntelliJRoot / "src/main/plugin.xml") }
}