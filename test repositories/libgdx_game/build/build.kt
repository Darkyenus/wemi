
// Testing only
@file:BuildClasspathDependency("../../build/wemi-plugin-jvm-hotswap-0.5-SNAPSHOT.jar")

import wemi.Keys.runOptions
import wemi.dependency.Repository
import wemi.util.executable

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
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-platform", gdxVersion, Repository.M2.Classifier to "natives-desktop") }

    projectDependencies add { dependency(core, true) }

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

    //val LocalM2Repository = Repository.M2("local", URL("file", "localhost", (projectRoot / "m2/").absolutePath + "/"), null)
    //val CentralM2Repository = Repository.M2("central", URL("https://repo1.maven.org/maven2/"), LocalM2Repository)

    //repositories set { listOf(CentralM2Repository, LocalM2Repository) }

    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-backend-lwjgl", gdxVersion) }
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-platform", gdxVersion, Repository.M2.Classifier to "natives-desktop") }

    projectDependencies add { dependency(core, true) }

    mainClass set {"game.Main"}
}
