import wemi.Archetypes
import wemi.Configurations.compilingJava
import wemi.Configurations.compilingKotlin
import wemi.Keys
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.MergeStrategy
import wemi.boot.WemiCacheFolder
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinCompilerVersion
import wemi.createProject
import wemi.dependency.JCenter
import wemi.dependency.Jitpack
import wemi.publish.InfoNode
import wemi.util.*
import java.nio.file.Files

val CompilerProjects = listOf(
        createKotlinCompilerProject("1.1.61"),
        createKotlinCompilerProject("1.2.21"),
        createKotlinCompilerProject("1.2.41")
)

private const val WemiGroup = "com.darkyen.wemi"
private const val WemiVersion = "0.5-SNAPSHOT"

/**
 * Wemi Build System core
 */
val core:Project by project {
    projectGroup set { WemiGroup }
    projectName set { "wemi-core" }
    projectVersion set { WemiVersion }

    mainClass set { "wemi.boot.MainKt" }

    repositories add { Jitpack }

    libraryDependencies set { wSetOf(
            kotlinDependency("stdlib"),
            kotlinDependency("reflect"),
            dependency("org.slf4j", "slf4j-api", "1.7.25"),
            dependency("com.github.Darkyenus", "tproll", "v1.2.6"),
            dependency("com.github.Darkyenus", "DaveWebb", "v1.2"),
            dependency("com.github.EsotericSoftware", "jsonbeans", "0.9"),
            dependency("org.jline", "jline", "3.3.0")
        ) }

    // Compile-only (provided) libraries
    extend(compiling) {
        libraryDependencies add {
            /* Used ONLY in wemi.test.forked.TestLauncher */
            dependency("org.junit.platform", "junit-platform-launcher", "1.0.2")
        }

        libraryDependencies add {
            /* Used only in wemi.document.DokkaInterface */
            wemi.dependency("org.jetbrains.dokka", "dokka-fatjar", "0.9.15", preferredRepository = JCenter)
        }
    }

    extend(compilingKotlin) {
        compilerOptions[KotlinCompilerFlags.customFlags] += "-Xskip-runtime-version-check"
        compilerOptions[KotlinCompilerFlags.incremental] = true
    }

    extend(testing) {
        libraryDependencies add { JUnitAPI }
        libraryDependencies add { JUnitEngine }
    }

    extend(assembling) {
        // Add all compiler frontends to the internal classpath
        internalClasspath modify { cp ->
            val cpWithCompilers = WMutableList(cp)

            for (p in CompilerProjects) {
                p.evaluate {
                    cpWithCompilers.addAll(internalClasspath.get())
                }
            }

            cpWithCompilers
        }

        // Add .jar with sources to resource files
        resourceFiles add {
            AssemblyOperation().use { asOp ->
                val sourcePath = WemiCacheFolder / "source.zip"

                for (file in using(compilingJava) { Keys.sourceFiles.get() }) {
                    asOp.addSource(file, true, false)
                }
                for (file in using(compilingKotlin) { Keys.sourceFiles.get() }) {
                    asOp.addSource(file, true, false)
                }

                asOp.assembly({ MergeStrategy.Deduplicate }, DefaultRenameFunction, sourcePath, byteArrayOf(), false)

                LocatedFile(sourcePath)
            }
        }
    }

    assemblyPrependData set {
        Files.readAllBytes(buildDirectory.get() / "WemiPrepend.sh")
    }

    assemblyOutputFile set {
        val assemblyDir = buildDirectory.get() / "assembly"
        Files.createDirectories(assemblyDir)
        assemblyDir / "wemi"
    }

    assembly modify { assembled ->
        assembled.executable = true
        assembled
    }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi",
                "Wonders Expeditiously, Mundane Instantly - Simple yet powerful build system - core jar",
                "2017"
                )
    }
}

fun setupSharedPublishMetadata(metadata:InfoNode, name:String, description:String, inceptionYear:String): InfoNode {
    return metadata.apply {
        child("name", name)
        child("description", description)
        child("url", "https://github.com/Darkyenus/WEMI")

        child("inceptionYear", inceptionYear)

        child("scm") {
            newChild("connection", "scm:git:https://github.com/Darkyenus/WEMI")
            newChild("developerConnection", "scm:git:https://github.com/Darkyenus/WEMI")
            newChild("tag", "HEAD")
            newChild("url", "https://github.com/Darkyenus/WEMI")
        }

        child("issueManagement") {
            child("system", "GitHub Issues")
            child("url", "https://github.com/Darkyenus/WEMI/issues")
        }
    }
}

fun createKotlinCompilerProject(version:String):Project {
    val projectName = StringBuilder("kotlinc_")
    for (c in version) {
        if (c.isJavaIdentifierPart()) {
            projectName.append(c)
        } else {
            projectName.append('_')
        }
    }

    return createProject(projectName.toString(), path("src/main-kotlinc/$version"), Archetypes.JavaKotlinProject) {

        extendMultiple(compilingJava, compilingKotlin) {
            sourceRoots set { wSetOf(projectRoot.get() / "src") }
        }

        extend(compilingKotlin) {
            compilerOptions[KotlinCompilerFlags.customFlags] += "-Xskip-runtime-version-check"
        }

        projectDependencies set {
            wSetOf(dependency(core, false))
        }

        libraryDependencies set { wSetOf(
                dependency("org.jetbrains.kotlin", "kotlin-compiler", version),
                dependency("org.slf4j", "slf4j-api", "1.7.25")
        ) }

    }
}

/**
 * Plugin for hotswapping JVM code at runtime.
 */
val pluginJvmHotswap by project(path("plugins/jvm-hotswap")) {
    projectGroup set { WemiGroup }
    projectName set { "wemi-plugin-jvm-hotswap" }
    projectVersion set { WemiVersion }

    extend(compiling) {
        projectDependencies add { dependency(core, false) }
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

val jitpackBuild by configuration("Used when building for Jitpack") {
    // See jitpack.yml
    publishRepository set { repository("local", path("jitpack-out").toUri().toURL().toString()) }

    // For clarity, use the version Jitpack expects
    projectVersion modify { System.getenv("VERSION") ?: it }
}
