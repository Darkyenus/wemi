import wemi.dependency.ProjectDependency
import wemi.generation.Constant
import wemi.generation.generateKotlinConstantsFile
import wemi.generation.generateSources

/** Plugin for hotswapping JVM code at runtime. */
val pluginJvmHotswap by project(path("plugins/jvm-hotswap")) {
    projectGroup set { WemiGroup }
    projectName set { "wemi-plugin-jvm-hotswap" }
    projectVersion set { using(wemi) { projectVersion.get() } }

    projectDependencies add { ProjectDependency(core, false, scope=ScopeProvided) }
    libraryDependencies add { Dependency(JUnitAPI, scope=ScopeTest) }
    libraryDependencies add { Dependency(JUnitEngine, scope=ScopeTest) }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi Plugin: JVM Hotswap",
                "Adds JVM code hotswap integration for Wemi Build System",
                "2018"
        )
    }
}

val pluginTeaVM by project(path("plugins/teavm")) {
    projectGroup set { WemiGroup }
    projectName set { "wemi-plugin-teavm" }
    projectVersion set { using(wemi) { projectVersion.get() } }

    val TEAVM_VERSION = "0.6.1"

    generateSources {
        generateKotlinConstantsFile(it, "wemiplugin.teavm.Version",
                mapOf("TEAVM_VERSION" to Constant.StringConstant(TEAVM_VERSION, "The version of TeaVM to be used")))
    }

    projectDependencies add { ProjectDependency(core, false, scope=ScopeProvided) }
    libraryDependencies add { dependency("org.teavm", "teavm-tooling", TEAVM_VERSION) }
    libraryDependencies add { Dependency(JUnitAPI, scope=ScopeTest) }
    libraryDependencies add { Dependency(JUnitEngine, scope=ScopeTest) }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi Plugin: TeaVM support",
                "Compile Java bytecode to JavaScript using TeaVM",
                "2018"
        )
    }
}