import org.slf4j.LoggerFactory
import wemi.Archetypes
import wemi.BooleanValidator
import wemi.KeyDefaults.inProjectDependencies
import wemi.Keys.cacheDirectory
import wemi.WemiException
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.boot.WemiRootFolder
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinCompilerVersion
import wemi.createProject
import wemi.dependency
import wemi.dependency.JCenter
import wemi.dependency.Jitpack
import wemi.dependency.ProjectDependency
import wemi.expiresWith
import wemi.key
import wemi.publish.InfoNode
import wemi.read
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

val CompilerProjects = listOf(
        createKotlinCompilerProject("1.1.61"),
        createKotlinCompilerProject("1.2.71"),
        createKotlinCompilerProject("1.3.20"),
        createKotlinCompilerProject("1.3.41")
)

const val WemiGroup = "com.darkyen.wemi"

val distributionArchiveContent by key<Path>("Create a distribution archive content for Wemi (required for test repositories)")
val distributionArchive by key<Path>("Create a distribution archive for Wemi (for final distribution)")

val wemi:Project by project(Archetypes.AggregateJVMProject) {

    projectGroup set { WemiGroup }
    projectName set { "wemi-core" }
    projectVersion set {
        versionAccordingToGit() ?: "dev-${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}"
    }

    projectDependencies add { ProjectDependency(core, true) }
    projectDependencies addAll { CompilerProjects.map { ProjectDependency(it, true) } }
    projectDependencies add { ProjectDependency(dokkaInterfaceImplementation, true) }

    distributionArchiveContent set {
        val distFolder = Files.createDirectories(cacheDirectory.get() / "-distribution-archive")
        distFolder.ensureEmptyDirectory()
        expiresWith(distFolder)
        val distSourceFolder = Files.createDirectories(distFolder / "sources")

        Files.copy(archive.get()!!, distFolder / "wemi.jar")
        Files.copy(using(archivingSources) { archive.get()!! }, distSourceFolder / "wemi.jar")
        for (path in using(running) { externalClasspath.get() }) {
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
        val wemiVersion = projectVersion.get()
        val launcherScriptTemplatePath = WemiRootFolder / "src/launcher-template.sh"
        val launcherScriptPath = distFolder / "wemi"
        Files.newBufferedWriter(launcherScriptPath, Charsets.UTF_8).use { out ->
            launcherScriptTemplatePath.forEachLine { line ->
                out.write(line.replace("<<<WEMI_VERSION>>>", wemiVersion))
                out.write("\n")
            }
        }
        launcherScriptPath.executable = true

        // Pack tar archive
        system("tar", "-c", "-v", "-z", "-f", (distFolder / "wemi.tar.gz").absolutePath, *Files.list(archiveContentDir).map { it.fileName }.filter { !it.startsWith(".") }.map { "./$it" }.toArray { Array(it) { "" } }, workingDirectory = archiveContentDir, collectOutput = false) { code -> throw WemiException("tar($code)") }

        // Create build info document
        val buildInfo = distFolder / "build-info.txt"
        buildInfo.writeText(StringBuilder().apply {
            append("Wemi ").append(wemiVersion).append('\n')
            append("Git: ").append(system("git", "rev-parse", "HEAD")).append('\n')
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

    extend(archivingSources) {
        archive set {
            using(Configurations.archiving) {
                AssemblyOperation().use { assemblyOperation ->
                    // Load data
                    for (file in sources.getLocatedPaths()) {
                        assemblyOperation.addSource(file, true, extractJarEntries = false)
                    }

                    inProjectDependencies(true) {
                        for (file in sources.getLocatedPaths()) {
                            assemblyOperation.addSource(file, true, extractJarEntries = false)
                        }
                    }

                    val outputFile = archiveOutputFile.get()
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
        }
    }
    // end remove
}

/** Core functionality */
val core:Project by project {

    repositories add { Jitpack }

    val JLineVersion = "3.3.0"
    libraryDependencies set { setOf(
            latestKotlinDependency("stdlib"),
            latestKotlinDependency("reflect"),
            dependency("org.slf4j", "slf4j-api", "1.7.25"),
            dependency("com.darkyen", "tproll", "v1.3.0"),
            dependency("com.darkyen", "DaveWebb", "v1.2"),
            dependency("com.github.EsotericSoftware", "jsonbeans", "0.9"),
            dependency("org.jline", "jline-terminal", JLineVersion),
            dependency("org.jline", "jline-terminal-jansi", JLineVersion),
            dependency("org.jline", "jline-reader", JLineVersion)
        ) }

    /* Used ONLY in wemi.test.forked.TestLauncher */
    libraryDependencies add { Dependency(JUnitPlatformLauncher, scope=ScopeProvided) }

    compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }
    compilerOptions[KotlinCompilerFlags.incremental] = { true }
    compilerOptions[JavaCompilerFlags.customFlags] = { it + "-Xlint:all" }
}

fun latestKotlinDependency(name:String):Dependency {
    val latestKotlinVersion = KotlinCompilerVersion.values().last().string
    return dependency("org.jetbrains.kotlin", "kotlin-$name", latestKotlinVersion)
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
        sources set { FileSet(projectRoot.get() / "src") }

        compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }

        projectDependencies add { ProjectDependency(core, false, scope=ScopeProvided) }
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

    projectDependencies add { ProjectDependency(core, false, scope=ScopeProvided) }

    /* Used only in wemi.document.DokkaInterface */
    libraryDependencies add { dependency("org.jetbrains.dokka", "dokka-fatjar", "0.9.15", scope=ScopeProvided) }

    repositories set { setOf(JCenter) }
}

private val SYSTEM_LOG = LoggerFactory.getLogger("system")

fun system(vararg command:String, workingDirectory:Path = WemiRootFolder, timeoutMs:Long = 60_000L, collectOutput:Boolean = true, onFail:(code:Int?) -> Unit = {}):String? {
    val process = ProcessBuilder(*command).run {
        directory(workingDirectory.toFile())
        redirectError(ProcessBuilder.Redirect.INHERIT)
        redirectInput(ProcessBuilder.Redirect.INHERIT)
        if (collectOutput) {
            redirectOutput(ProcessBuilder.Redirect.PIPE)
        } else {
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
        }
        start()
    }

    val commandString = command.joinToString(" ")

    var result = ""
    val semaphore = Semaphore(0)

    if (collectOutput) {
        val readerThread = Thread({
            try {
                result = process.inputStream.reader(Charsets.UTF_8).readText()
            } finally {
                semaphore.release()
            }
        }, "Reader for $commandString")
        readerThread.isDaemon = true
        readerThread.start()
    } else {
        semaphore.release()
    }

    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        SYSTEM_LOG.error("Process $process ($commandString) timed out")
        process.destroyForcibly()
        onFail(null)
        return null
    }

    semaphore.tryAcquire(5000, TimeUnit.MILLISECONDS)
    val exitValue = process.exitValue()
    if (exitValue == 0) {
        return result.trimEnd()
    } else {
        onFail(exitValue)
        return null
    }
}

fun versionAccordingToGit():String? {
    val lastWemiVersionTag = system("git", "describe", "--tags", "--match=v*", "--abbrev=0") { SYSTEM_LOG.warn("Could not find Wemi version") } ?: return null
    val lastVersionCommit = system("git", "rev-list", "--max-count=1", lastWemiVersionTag) { SYSTEM_LOG.warn("Could not get version_commit") } ?: return null
    val latestCommit = system("git", "rev-list", "--max-count=1", "master") { SYSTEM_LOG.warn("Could not get the latest commit") } ?: return null

    val matcher = Pattern.compile("v([0-9]+)\\.([0-9]+).*").matcher(lastWemiVersionTag)
    if (!matcher.matches()) {
        SYSTEM_LOG.warn("Wemi version tag does not match the expected pattern: $lastWemiVersionTag")
        return null
    }
    val lastWemiVersionMajor = matcher.group(1)
    val lastWemiVersionMinor = matcher.group(2)

    return if(lastVersionCommit == latestCommit) {
        // We are at a release commit
        "$lastWemiVersionMajor.$lastWemiVersionMinor"
    } else {
        // We are at a snapshot commit for next dev version
        "$lastWemiVersionMajor.${lastWemiVersionMinor.toInt() + 1}-SNAPSHOT"
    }
}