import org.slf4j.LoggerFactory
import wemi.Archetypes
import wemi.BooleanValidator
import wemi.KeyDefaults.inProjectDependencies
import wemi.KeyDefaults.defaultArchiveFileName
import wemi.Keys.cacheDirectory
import wemi.WemiException
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.boot.WemiCacheFolder
import wemi.boot.WemiRootFolder
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.createProject
import wemi.dependency
import wemi.dependency.JCenter
import wemi.dependency.Jitpack
import wemi.dependency.ProjectDependency
import wemi.dependency.ScopeAggregate
import wemi.expiresWith
import wemi.generation.Constant
import wemi.generation.generateKotlinConstantsFile
import wemi.generation.generateSources
import wemi.key
import wemi.publish.InfoNode
import wemi.read
import wemi.run.system
import wemi.test.JUnitPlatformLauncher
import wemi.test.TestReport
import wemi.test.TestStatus
import wemi.test.prettyPrint
import wemi.util.FileSet
import wemi.util.absolutePath
import wemi.util.ensureEmptyDirectory
import wemi.util.executable
import wemi.util.forEachLine
import wemi.util.name
import wemi.util.writeText
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.regex.Pattern

val KotlinCompilerVersions = listOf(
        "1.1.61",
        "1.2.71",
        "1.3.20",
        "1.3.41",
        "1.3.61",
        "1.3.72",
        "1.4.10"
)

val CompilerProjects = KotlinCompilerVersions.map { createKotlinCompilerProject(it) }

const val WemiGroup = "com.darkyen.wemi"

val distributionArchiveContent by key<Path>("Create a distribution archive content for Wemi (required for test repositories)")
val distributionArchive by key<Path>("Create a distribution archive for Wemi (for final distribution)")
val wemiLauncher by key<Path>("Create a launcher script")

val wemi:Project by project(Archetypes.AggregateJVMProject) {

    projectGroup set { WemiGroup }
    projectName set { "wemi-core" }
    projectVersion set { gatherProjectVersion() }

    projectDependencies add { ProjectDependency(core, scope = ScopeAggregate) }
    projectDependencies addAll { CompilerProjects.map { ProjectDependency(it, scope = ScopeAggregate) } }
    projectDependencies add { ProjectDependency(dokkaInterfaceImplementation, scope = ScopeAggregate) }

    wemiLauncher set {
        val wemiVersion = projectVersion.get()
        val launcherScriptTemplatePath = WemiRootFolder / "src/launcher-template.sh"
        val launcherScriptPath = WemiCacheFolder / "-launcher-script/wemi"
        Files.createDirectories(launcherScriptPath.parent)
        Files.newBufferedWriter(launcherScriptPath, Charsets.UTF_8).use { out ->
            launcherScriptTemplatePath.forEachLine { line ->
                out.write(line.replace("<<<WEMI_VERSION>>>", wemiVersion))
                out.write("\n")
            }
        }
        launcherScriptPath.executable = true
        launcherScriptPath
    }

    distributionArchiveContent set {
        val distFolder = Files.createDirectories(cacheDirectory.get() / "-distribution-archive")
        distFolder.ensureEmptyDirectory()
        expiresWith(distFolder)
        val distSourceFolder = Files.createDirectories(distFolder / "sources")

        Files.copy(archive.get()!!, distFolder / "wemi.jar")
        Files.copy(archiveSources.get(), distSourceFolder / "wemi.jar")
        for (path in externalClasspath.getLocatedPathsForScope(setOf(ScopeCompile, ScopeRuntime /* no aggregate! */))) {
            Files.copy(path.file, distFolder / path.file.name)
        }

        distFolder
    }

    distributionArchive set {
        if (read("skipTest", "Skip test evaluation", BooleanValidator) != true) {
            val testResult: TestReport = test.get()
            if (testResult.values.any { it.status == TestStatus.FAILED }) {
                println(testResult.prettyPrint())
                throw WemiException("Can't build distribution archive when the tests are failing", showStacktrace = false)
            }
        }

        val archiveContentDir = distributionArchiveContent.get()

        val distFolder = Files.createDirectories(buildDirectory.get() / "dist")
        distFolder.ensureEmptyDirectory()
        expiresWith(distFolder)

        // Prepare launcher script
        val launcherScriptPath = distFolder / "wemi"
        Files.copy(wemiLauncher.get(), launcherScriptPath)
        launcherScriptPath.executable = true

        // Pack tar archive
        system("tar", "-c", "-v", "-z", "-f", (distFolder / "wemi.tar.gz").absolutePath, *Files.list(archiveContentDir).map { it.fileName }.filter { !it.startsWith(".") }.map { "./$it" }.toArray { Array(it) { "" } }, workingDirectory = archiveContentDir, timeoutMs = 60_000, collectOutput = false) { code, _ -> throw WemiException("tar($code)") }

        // Create build info document
        val buildInfo = distFolder / "build-info.txt"
        buildInfo.writeText(StringBuilder().apply {
            append("Wemi ").append(projectVersion.get()).append('\n')
            append("Git: ").append(system("git", "rev-parse", "HEAD", timeoutMs = 60_000)).append('\n')
            append("Date: ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().with(ChronoField.NANO_OF_SECOND, 0L))).append('\n')
        })

        distFolder
    }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi",
                "Wonders Expeditiously, Mundane Instantly - Simple yet powerful build system - core jar",
                "2017"
        )
    }

    // When publishing on jitpack, automatically publish all plugins as well
    extend(Configurations.jitpack) {
        publish modify {
            for (p in arrayOf(pluginJvmHotswap, pluginTeaVM, pluginIntellij)) {
                using(p) {
                    publish.get()
                }
            }
            it
        }
    }

    archiveSources set {
        AssemblyOperation().use { assemblyOperation ->
            // Load data
            for (file in sources.getLocatedPaths()) {
                assemblyOperation.addSource(file, true, extractJarEntries = false)
            }

            inProjectDependencies { dep ->
                if (dep.scope == ScopeAggregate) {
                    for (file in sources.getLocatedPaths()) {
                        assemblyOperation.addSource(file, true, extractJarEntries = false)
                    }
                }
            }

            val outputFile = defaultArchiveFileName("sources", extension = "jar")
            assemblyOperation.assembly(
                    NoConflictStrategyChooser,
                    DefaultRenameFunction,
                    DefaultAssemblyMapFilter,
                    outputFile,
                    NoPrependData,
                    compress = true)

            expiresWith(outputFile)
            outputFile
        }
    }
    // end remove
}

/** Core functionality */
val core:Project by project {

    projectVersion set { using(wemi) { projectVersion.get() } }
    generateSources("core-version") {
        generateKotlinConstantsFile(it, "wemi.boot.Version", mapOf(
                "WemiVersion" to Constant.StringConstant(projectVersion.get(), "Version of Wemi build system"),
                "WemiBuildCommit" to Constant.StringConstant(lastGitCommit() ?: "", "Git commit out of which this build was built")
        ))
    }

    repositories add { Jitpack }

    val JLineVersion = "3.3.0"
    libraryDependencies set { setOf(
            latestKotlinDependency("stdlib"),
            latestKotlinDependency("reflect"),
            dependency("org.slf4j", "slf4j-api", "1.7.30"),
            dependency("com.darkyen", "tproll", "1.3.2"),
            dependency("com.darkyen", "DaveWebb", "v1.2"),
            dependency("com.github.EsotericSoftware", "jsonbeans", "c3520fcc51"),
            dependency("org.jline", "jline-terminal", JLineVersion),
            dependency("org.jline", "jline-terminal-jansi", JLineVersion),
            dependency("org.jline", "jline-reader", JLineVersion)
        ) }

    // This must be done manually the old school way, because we need some of these in normal compilation as well
    // and mixing the two approaches is not great
    extend(testing) {
        libraryDependencies addAll { listOf(
                Dependency(JUnitAPI, scope=ScopeTest),
                Dependency(JUnitEngine, scope=ScopeTest),
                Dependency(JUnitPlatformLauncher, scope=ScopeTest)
        ) }
    }
    // Add these when importing from IDE, because IDE does not see it otherwise
    extend(Configurations.ideImport) {
        libraryDependencies addAll { listOf(
                Dependency(JUnitAPI, scope=ScopeTest),
                Dependency(JUnitEngine, scope=ScopeTest),
                Dependency(JUnitPlatformLauncher, scope=ScopeTest)
        ) }
    }

    /* Used ONLY in wemi.test.forked.TestLauncher */
    libraryDependencies add { Dependency(JUnitPlatformLauncher, scope=ScopeProvided) }

    compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }
    compilerOptions[KotlinCompilerFlags.incremental] = { true }
    compilerOptions[JavaCompilerFlags.customFlags] = { it + "-Xlint:all" }
}

fun latestKotlinDependency(name:String):Dependency {
    return dependency("org.jetbrains.kotlin", "kotlin-$name", KotlinCompilerVersions.last())
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

    return createProject(projectName.toString(), path("src/main-kotlinc/$version"), Archetypes.JavaKotlinProject, Archetypes.JUnitLayer) {
        sources set { FileSet(projectRoot.get() / "src") }

        compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }

        projectDependencies add { ProjectDependency(core, scope=ScopeProvided) }
        // Disable default kotlin stdlib
        libraryDependencies set { setOf(
                dependency("org.jetbrains.kotlin", "kotlin-compiler", version, scope=ScopeProvided)
        ) }
    }
}

// Separate project, because Dokka is distributed through a fat-jar, which also includes kotlin stdlib.
// This caused compile problems because it leads to two stdlibs on the classpath for the main project.
val dokkaInterfaceImplementation by project(path("src/main-dokka")) {
    sources set { FileSet(projectRoot.get() / "src") }

    compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }

    libraryDependencies set { emptySet() } // Disable default kotlin stdlib

    projectDependencies add { ProjectDependency(core, scope=ScopeProvided) }

    // See https://bintray.com/kotlin/dokka/dokka for latest version number
    /* Used only in wemi.document.DokkaInterface */
    libraryDependencies add { dependency("org.jetbrains.dokka", "dokka-fatjar", "0.9.15", scope=ScopeProvided) }
    // libraryDependencies add { dependency("org.jetbrains.dokka", "dokka-cli", "1.4.10.2", scope=ScopeProvided) }

    repositories set { setOf(JCenter) }
}
