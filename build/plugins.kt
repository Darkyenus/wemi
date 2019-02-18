import wemi.dependency.ProjectDependency

/**
 * Plugin for hotswapping JVM code at runtime.
 */
val pluginJvmHotswap by project(path("plugins/jvm-hotswap")) {
    projectGroup set { WemiGroup }
    projectName set { "wemi-plugin-jvm-hotswap" }
    projectVersion set { WemiVersion }

    extend(compiling) {
        projectDependencies add { ProjectDependency(core, false) }
    }

    extend(testing) {
        libraryDependencies add { JUnitAPI }
        libraryDependencies add { JUnitEngine }
    }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi Plugin: JVM Hotswap",
                "Adds JVM code hotswap integration for Wemi Build System",
                "2018"
        )
    }
}
