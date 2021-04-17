@file:BuildDependencyPlugin("wemi-plugin-intellij")
@file:BuildDependencyRepository("jcenter", "https://jcenter.bintray.com/")

import wemi.*
import wemi.keys.*
import wemiplugin.intellij.*

val testPlugin by project(Archetypes.JavaProject, IntelliJPluginLayer) {

    projectGroup set { "com.darkyen" }
    projectName set { "test-plugin" }
    projectVersion set { "1.0-SNAPSHOT" }

    IntelliJ.intellijIdeDependency set { IntelliJIDE.External(version = "201.8743.12") }
    IntelliJ.intellijPluginXmlFiles add { LocatedPath(path("src/plugin.xml")) }
    IntelliJ.intellijPluginDependencies add { IntelliJPluginDependency.External("com.darkyen.darkyenustimetracker", "1.5.1") }
}