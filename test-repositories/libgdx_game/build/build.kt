
// Testing only
@file:BuildClasspathDependency("../../build/wemi-plugin-jvm-hotswap-0.7-SNAPSHOT.jar")

import wemi.Keys.runOptions
import wemi.util.executable
import wemi.dependency.*
import wemi.*

val gdxVersion = "1.9.7"

val core by project(path("core")) {
    projectName set {"LibGDX Demo"}
    projectGroup set {"wemi"}
    projectVersion set {"1.0"}

    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx", gdxVersion) }
}

val lwjgl3 by project(path("lwjgl3")) {

    projectName set {"LibGDX Demo"}
    projectGroup set {"wemi"}
    projectVersion set {"1.0"}

    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-backend-lwjgl3", gdxVersion) }
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-platform", gdxVersion, classifier = "natives-desktop") }

    projectDependencies add { ProjectDependency(core, true) }

    runOptions add {"-XstartOnFirstThread"}
    mainClass set {"game.Main"}

    assemblyPrependData set {
        "#!/usr/bin/env sh\nexec java -XstartOnFirstThread -jar \"$0\" \"$@\"\n".toByteArray(Charsets.UTF_8)
    }

    assemblyOutputFile set {
        buildDirectory.get() / "game"
    }

    assembly modify { assembled ->
        assembled.executable = true
        assembled
    }
}

val lwjgl2 by project(path("./lwjgl2/")) {
    projectName set {"LibGDX Demo"}
    projectGroup set {"wemi"}
    projectVersion set {"1.0"}


    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-backend-lwjgl", gdxVersion) }
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-platform", gdxVersion, classifier = "natives-desktop") }

    projectDependencies add { ProjectDependency(core, true) }

    mainClass set {"game.Main"}
}
