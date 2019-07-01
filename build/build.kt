import wemi.*
import wemi.Archetypes
import wemi.Configurations.compilingJava
import wemi.Configurations.compilingKotlin
import wemi.Keys
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.MergeStrategy
import wemi.boot.WemiCacheFolder
import wemi.collections.toMutable
import wemi.compile.KotlinCompilerFlags
import wemi.dependency.DefaultExclusions
import wemi.dependency.JCenter
import wemi.dependency.Jitpack
import wemi.dependency.ProjectDependency
import wemi.publish.InfoNode
import wemi.util.FileSet
import wemi.util.executable
import wemi.util.name
import wemi.util.plus
import java.nio.charset.StandardCharsets
import java.nio.file.Files

val CompilerProjects = listOf(
        createKotlinCompilerProject("1.1.61"),
        createKotlinCompilerProject("1.2.21"),
        createKotlinCompilerProject("1.2.41"),
        createKotlinCompilerProject("1.2.71"),
        createKotlinCompilerProject("1.3.20")
)

const val WemiGroup = "com.darkyen.wemi"
const val ThisWemiVersion = "0.9-SNAPSHOT" // as opposed to the generic WemiVersion, which is the version with which we build

/**
 * Wemi Build System core
 */
val core:Project by project {
    projectGroup set { WemiGroup }
    projectName set { "wemi-core" }
    projectVersion set { ThisWemiVersion }

    mainClass set { "wemi.boot.PreMain" }

    repositories add { Jitpack }

    val JLineVersion = "3.3.0"
    libraryDependencies set { setOf(
            dependency("org.slf4j", "slf4j-api", "1.7.25"),
            dependency("com.darkyen", "tproll", "v1.3.0"),
            dependency("com.darkyen", "DaveWebb", "v1.2"),
            dependency("com.github.EsotericSoftware", "jsonbeans", "0.9"),
            // TODO Explicit excludes because Maven2 resolution is messed up again and it tried to include some testing libraries
            Dependency(DependencyId("org.jline", "jline-terminal", JLineVersion), DefaultExclusions + DependencyExclusion("junit", "junit") + DependencyExclusion("org.easymock", "easymock")),
            Dependency(DependencyId("org.jline", "jline-terminal-jansi", JLineVersion), DefaultExclusions + DependencyExclusion("junit", "junit") + DependencyExclusion("org.easymock", "easymock")),
            Dependency(DependencyId("org.jline", "jline-reader", JLineVersion), DefaultExclusions + DependencyExclusion("junit", "junit") + DependencyExclusion("org.easymock", "easymock"))
        ) }

    // Compile-only (provided) libraries
    extend(compiling) {
        projectDependencies add { ProjectDependency(kotlinStdlib, true) }
        libraryDependencies add {
            /* Used ONLY in wemi.test.forked.TestLauncher */
            dependency("org.junit.platform", "junit-platform-launcher", "1.0.2")
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
            val cpWithCompilers = cp.toMutable()

            for (p in CompilerProjects) {
                p.evaluate {
                    cpWithCompilers.addAll(internalClasspath.get())
                }
            }

            cpWithCompilers.addAll(dokkaInterfaceImplementation.evaluate { internalClasspath.get() })
            
            cpWithCompilers
        }

        // Add .jar with sources to resource files
        resources modify { oldResources ->
            val sourcePath = WemiCacheFolder / "source.zip"
            AssemblyOperation().use { asOp ->
                for (file in Keys.sources.getLocatedPaths()) {
                    asOp.addSource(file, true, extractJarEntries = false)
                }

                asOp.assembly({ MergeStrategy.Deduplicate }, DefaultRenameFunction, DefaultAssemblyMapFilter, sourcePath, byteArrayOf(), false)
            }

            oldResources + FileSet(sourcePath)
        }

        // Add kotlin stdlib as a bundled jar
        resources modify { oldResources ->
            var resources = oldResources

            val libraryList = StringBuilder()
            for (stdlibJar in kotlinStdlib.evaluate{ externalClasspath.get() }) {
                val jarEntry = stdlibJar.classpathEntry
                resources += FileSet(jarEntry)
                libraryList.append(jarEntry.name).append('\n')
            }

            val assemblyFolder = WemiCacheFolder / "-assembly-libraries"
            Files.createDirectories(assemblyFolder)
            val librariesPath = assemblyFolder / "libraries"
            Files.newBufferedWriter(librariesPath, StandardCharsets.UTF_8).use {
                it.append(libraryList)
            }
            resources += FileSet(librariesPath)

            resources
        }
    }

    assemblyPrependData set {
        val prependFile = buildDirectory.get() / "WemiPrepend.sh"
        expiresWith(prependFile)
        Files.readAllBytes(prependFile)
    }

    assemblyOutputFile set {
        val assemblyDir = buildDirectory.get() / "assembly"
        expiresWith(assemblyDir)
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

    run set { println("This is not a good idea"); -1 }
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

        extend(compilingJava) {
            sources set { null }
        }

        extend(compilingKotlin) {
            sources set { FileSet(projectRoot.get() / "src") }
            compilerOptions[KotlinCompilerFlags.customFlags] += "-Xskip-runtime-version-check"
        }

        projectDependencies set {
            setOf(ProjectDependency(core, false))
        }

        libraryDependencies set { setOf(
                dependency("org.jetbrains.kotlin", "kotlin-compiler", version),
                dependency("org.slf4j", "slf4j-api", "1.7.25")
        ) }

    }
}

val dokkaInterfaceImplementation by project(path("src/main-dokka")) {
    extend(compilingJava) {
        sources set { null }
    }

    extend(compilingKotlin) {
        sources set { FileSet(projectRoot.get() / "src") }
        compilerOptions[KotlinCompilerFlags.customFlags] += "-Xskip-runtime-version-check"
    }

    projectDependencies set {
        setOf(ProjectDependency(core, false))
    }

    extend(compiling) {
        libraryDependencies add {
            /* Used only in wemi.document.DokkaInterface */
            dependency("org.jetbrains.dokka", "dokka-fatjar", "0.9.15", preferredRepository = JCenter)
        }
    }
}

val kotlinStdlib by project(Archetypes.BlankJVMProject) {
    libraryDependencies set { setOf(kotlinDependency("stdlib"), kotlinDependency("reflect")) }
    assemblyOutputFile set { Keys.cacheDirectory.get() / "kotlin-stdlib-assembly.zip" } // TODO(jp): .jar, but now it gets flattened
}