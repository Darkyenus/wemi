import wemi.Archetypes.JUnitLayer
import wemi.Archetypes.JavaKotlinProject
import wemi.dependency.JCenter
import wemi.dependency.ProjectDependency
import wemi.generation.Constant
import wemi.generation.generateKotlinConstantsFile
import wemi.generation.generateSources

/** Plugin for hotswapping JVM code at runtime. */
val pluginJvmHotswap by project(path("plugins/jvm-hotswap"), JavaKotlinProject, JUnitLayer) {
    projectGroup set { WemiGroup }
    projectName set { "wemi-plugin-jvm-hotswap" }
    projectVersion set { using(wemi) { projectVersion.get() } }

    projectDependencies add { ProjectDependency(core, scope=ScopeProvided) }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi Plugin: JVM Hotswap",
                "Adds JVM code hotswap integration for Wemi Build System",
                "2018"
        )
    }
}

/** Plugin which translates Java bytecode to JavaScript through TeaVM.
 * https://github.com/konsoletyper/teavm */
val pluginTeaVM by project(path("plugins/teavm"), JavaKotlinProject, JUnitLayer) {
    projectGroup set { WemiGroup }
    projectName set { "wemi-plugin-teavm" }
    projectVersion set { using(wemi) { projectVersion.get() } }

    val TEAVM_VERSION = "0.6.1"

    generateSources("plugin-teavm-version") {
        generateKotlinConstantsFile(it, "wemiplugin.teavm.Version",
                mapOf("TEAVM_VERSION" to Constant.StringConstant(TEAVM_VERSION, "The version of TeaVM to be used")))
    }

    projectDependencies add { ProjectDependency(core, scope=ScopeProvided) }
    libraryDependencies add { dependency("org.teavm", "teavm-tooling", TEAVM_VERSION) }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi Plugin: TeaVM support",
                "Compile Java bytecode to JavaScript using TeaVM",
                "2020"
        )
    }
}

/** Plugin for building IntelliJ platform plugins.
 * Based on https://github.com/JetBrains/gradle-intellij-plugin */
val pluginIntellij by project(path("plugins/intellij"), JavaKotlinProject, JUnitLayer) {
    projectGroup set { WemiGroup }
    projectName set { "wemi-plugin-intellij" }
    projectVersion set { using(wemi) { projectVersion.get() } }

    projectDependencies add { ProjectDependency(core, scope=ScopeProvided) }
    libraryDependencies add { dependency("org.apache.commons:commons-compress:1.20") }

    // Exclude stdlib that is in intellij dependencies and include ours, with correct version
    val excludeKotlinStdlib = listOf(
            DependencyExclusion(group = "org.jetbrains.kotlin", name = "kotlin-stdlib"),
            DependencyExclusion(group = "org.jetbrains.kotlin", name = "kotlin-stdlib-common"),
            DependencyExclusion(group = "org.jetbrains.kotlin", name = "kotlin-stdlib-jdk7"),
            DependencyExclusion(group = "org.jetbrains.kotlin", name = "kotlin-stdlib-jdk8"),
            DependencyExclusion(group = "org.jetbrains.kotlin", name = "kotlin-reflect")
    )
    libraryDependencies add { kotlinDependency("stdlib-jdk8") }
    libraryDependencies add { kotlinDependency("stdlib-jdk7") }

    repositories add { JCenter }
    //repositories add { Repository("jetbrains-bintray-intellij-plugin-service", "https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-plugin-service") }
    libraryDependencies add { dependency("org.jetbrains.intellij.plugins:structure-base:3.139", exclusions = excludeKotlinStdlib) }
    libraryDependencies add { dependency("org.jetbrains.intellij.plugins:structure-intellij:3.139", exclusions = excludeKotlinStdlib) }
    libraryDependencies add { dependency("org.jetbrains.intellij:blockmap:1.0.5", exclusions = excludeKotlinStdlib) }
    libraryDependencies add { dependency("org.jetbrains.intellij:plugin-repository-rest-client:2.0.15", exclusions = excludeKotlinStdlib) }

    // Instrumentation dependencies
    repositories add { Repository("idea-www.jetbrains.com_intellij-repository-releases", "https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases") }
    repositories add { Repository("intellij-third-party-dependencies", "https://jetbrains.bintray.com/intellij-third-party-dependencies") }
    libraryDependencies add { dependency("com.jetbrains.intellij.java", "java-compiler-ant-tasks", "201.8743.12", scope=ScopeProvided) }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi Plugin: IntelliJ plugin build support",
                "Build IntelliJ platform plugins",
                "2020"
        )
    }
}