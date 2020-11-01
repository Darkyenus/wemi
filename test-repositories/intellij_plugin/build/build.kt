@file:BuildDependency("com.darkyen.wemi", "wemi-plugin-intellij", "0.14-SNAPSHOT")
//@file:BuildDependencyPlugin("wemi-plugin-intellij")
@file:BuildDependencyRepository("jcenter", "https://jcenter.bintray.com/")

import wemi.*
import wemiplugin.intellij.*

val testPlugin by project(Archetypes.JavaProject, IntelliJPluginLayer) {

    projectGroup set { "com.darkyen" }
    projectName set { "test-plugin" }
    projectVersion set { "1.0-SNAPSHOT" }

    IntelliJ.intellijIdeDependency set { IntelliJIDE.External(version = "202.7660.26") }
    IntelliJ.intelliJPluginXmlFiles add { LocatedPath(path("src/plugin.xml")) }
    IntelliJ.intellijPluginDependencies add { IntelliJPluginDependency.External("com.darkyen.darkyenustimetracker", "1.5.1") }
}