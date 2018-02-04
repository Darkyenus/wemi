package wemi.boot

import org.slf4j.LoggerFactory
import wemi.WemiKotlinVersion
import wemi.WemiVersion
import wemi.WemiVersionIsSnapshot
import wemi.compile.CompilerFlags
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.dependency.*
import wemi.dependency.DependencyResolver.artifacts
import wemi.util.*
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val LOG = LoggerFactory.getLogger("BuildFiles")

/**
 * Extensions that a valid Wemi build script file have.
 * Without leading dot.
 */
val WemiBuildFileExtensions = listOf("wemi", "kt")

/**
 * Build file is a file with [WemiBuildFileExtensions], in [buildFolder].
 */
internal fun findBuildScriptSources(buildFolder: Path): List<Path> {
    var result: ArrayList<Path>? = null

    if (buildFolder.isDirectory()) {
        Files.newDirectoryStream(buildFolder).use { stream ->
            for (path in stream) {
                if (!path.isDirectory() && !path.isHidden() && !path.name.startsWith('.')
                        && path.name.pathHasExtension(WemiBuildFileExtensions)) {
                    var r = result
                    if (r == null) {
                        r = ArrayList()
                        result = r
                    }
                    r.add(path)
                }
            }
        }
    }

    return result ?: emptyList()
}

/**
 * Retrieve the compiled build script.
 *
 * Tries to use existing files before compiling.
 */
internal fun getBuildScript(rootFolder: Path, buildFolder: Path, buildScriptSources: List<Path>, forceCompile: Boolean): BuildScript? {
    val cacheFolder = buildFolder / "cache"
    if (cacheFolder.exists() && !cacheFolder.isDirectory()) {
        LOG.error("Build directory {} exists and is not a directory", cacheFolder)
        return null
    } else if (!cacheFolder.exists()) {
        try {
            Files.createDirectories(cacheFolder)
        } catch (e: IOException) {
            LOG.error("Could not create build directory {}", cacheFolder, e)
            return null
        }
    }

    val combinedBuildFileName = buildScriptSources.joinToString("-") { it.name.pathWithoutExtension() }

    val resultJar = cacheFolder / (combinedBuildFileName + "-cache.jar")
    val classpathFile = cacheFolder / (combinedBuildFileName + ".classpath")
    val classpath = WMutableList<Path>()

    var recompileReason = ""

    val recompile = if (forceCompile) {
        recompileReason = "Requested"
        LOG.debug("Rebuilding build scripts: Requested")
        true
    } else if (!resultJar.exists() || resultJar.isDirectory()) {
        recompileReason = "No cache"
        LOG.debug("Rebuilding build scripts: No cache at {}", resultJar)
        true
    } else if (resultJar.lastModified.let { jarLastModified -> buildScriptSources.any { source -> jarLastModified < source.lastModified } }) {
        recompileReason = "Script changed"
        LOG.debug("Rebuilding build scripts: Old cache")
        true
    } else if (!classpathFile.exists() || classpathFile.isDirectory()) {
        recompileReason = "Missing cache metadata"
        LOG.debug("Rebuilding build scripts: No classpath cache")
        true
    } else if (WemiLauncherFile.lastModified > resultJar.lastModified) {
        recompileReason = "Updated launcher"
        LOG.debug("Rebuilding build scripts: Launcher updated ({})", WemiLauncherFile)
        true
    } else {
        // All seems good, try to load the result classpath
        classpathFile.forEachLine { line ->
            if (line.isNotBlank()) {
                classpath.add(Paths.get(line))
            }
        }
        var recompile = false
        for (file in classpath) {
            if (!file.exists()) {
                recompileReason = "Corrupted cache metadata"
                LOG.debug("Rebuilding build scripts: Classpath cache corrupted ({} does not exist)", file)
                classpath.clear()
                recompile = true
                break
            }
        }
        if (!recompile) {
            if (classpath.isEmpty()) {
                recompileReason = "Cache is missing launcher"
                recompile = true
            } else if (!Files.isSameFile(WemiLauncherFile, classpath[0])) {
                recompileReason = "First classpath item does not point to a Wemi launcher file"
                recompile = true
            }
        }
        recompile
    }

    val buildFlags = CompilerFlags()
    buildFlags[KotlinJVMCompilerFlags.compilingWemiBuildFiles] = true
    buildFlags[KotlinCompilerFlags.moduleName] = combinedBuildFileName
    buildFlags[KotlinJVMCompilerFlags.jvmTarget] = "1.8"

    val sources = buildScriptSources.map { LocatedFile(it) }.toWList()

    val classpathConfiguration = BuildScriptClasspathConfiguration(buildScriptSources)

    if (recompile) {
        // Recompile
        LOG.info("Compiling build script: {}", recompileReason)
        resultJar.deleteRecursively()

        // Wemi also contains Kotlin runtime
        classpath.add(wemiLauncherFileWithJarExtension(cacheFolder)) // This must be first in classpath
        val resolved = HashMap<DependencyId, ResolvedDependency>()
        val resolutionOk = DependencyResolver.resolve(resolved, classpathConfiguration.dependencies, classpathConfiguration.repositoryChain)
        if (!resolutionOk) {
            LOG.warn("Failed to retrieve all build file dependencies for {}:\n{}", sources, resolved.prettyPrint(classpathConfiguration.dependencies.map { it.dependencyId }))
            return null
        }
        classpath.addAll(resolved.artifacts())

        classpathFile.writeText(classpath.joinToString(separator = "\n") { file -> file.absolutePath })

        // Compilation is handled lazily later, in BuildScript.ready()
    }

    return BuildScript(
            rootFolder, resultJar, buildFolder, cacheFolder,
            classpath,
            // Figure out the init class
            buildScriptSources.map { transformFileNameToKotlinClassName(it.name.pathWithoutExtension()) }.toWList(),
            classpathConfiguration, sources,
            buildFlags, !recompile)
}

private fun wemiLauncherFileWithJarExtension(cacheFolder: Path): Path {
    val wemiLauncherFile = WemiLauncherFile
    if (wemiLauncherFile.name.endsWith(".jar", ignoreCase = true) || wemiLauncherFile.isDirectory()) {
        LOG.debug("WemiLauncherFileWithJar is unchanged {}", wemiLauncherFile)
        return wemiLauncherFile
    }
    // We have create a link to/copy of the launcher file somewhere and name it with .jar

    val name = if (WemiVersionIsSnapshot) {
        val digest = wemiLauncherFile.hash("MD5")
        val sb = StringBuilder()
        sb.append(WemiVersion)
        sb.append('.')
        sb.append(toHexString(digest))
        sb.append(".jar")
        sb.toString()
    } else {
        "wemi-$WemiVersion.jar"
    }

    val wemiLauncherLinksDirectory = cacheFolder / "wemi-launcher-links"
    Files.createDirectories(wemiLauncherLinksDirectory)
    val linked = wemiLauncherLinksDirectory / name

    if (Files.exists(linked) && Files.getLastModifiedTime(wemiLauncherFile) < Files.getLastModifiedTime(linked)) {
        // Already exists and is fresh
        LOG.debug("WemiLauncherFileWithJar is existing {}", linked)
        return linked
    }

    Files.deleteIfExists(linked)
    try {
        val result = Files.createSymbolicLink(linked, wemiLauncherFile)
        LOG.debug("WemiLauncherFileWithJar is just linked {}", result)
        return result
    } catch (e: Exception) {
        LOG.warn("Failed to link {} to {}, copying", wemiLauncherFile, linked, e)

        try {
            Files.copy(wemiLauncherFile, linked)
            LOG.debug("WemiLauncherFileWithJar is just copied {}", linked)
            return linked
        } catch (e: Exception) {
            LOG.warn("Failed to copy {} to {}, returning non-jar file", wemiLauncherFile, linked, e)
            return wemiLauncherFile
        }
    }
}

/**
 * Transform the [fileNameWithoutExtension] into the name of class that will Kotlin compiler produce (without .class).
 */
private fun transformFileNameToKotlinClassName(fileNameWithoutExtension: String): String {
    val sb = StringBuilder()
    // If file name starts with digit, _ is prepended
    if (fileNameWithoutExtension.isNotEmpty() && fileNameWithoutExtension[0] in '0'..'9') {
        sb.append('_')
    }
    // Everything is valid java identifier
    for (c in fileNameWithoutExtension) {
        if (c.isJavaIdentifierPart()) {
            sb.append(c)
        } else {
            sb.append("_")
        }
    }
    // First letter is capitalized
    if (sb.isNotEmpty()) {
        sb[0] = sb[0].toUpperCase()
    }
    // Kt is appended
    sb.append("Kt")
    return sb.toString()
}

/**
 * Regex used to find lines with directives.
 *
 * Directives must begin line with `///` then have directory identifier and then arbitrary text for the directive.
 */
private val DirectiveRegex = "///\\s*([a-zA-Z0-9\\-]+)\\s+(.*?)\\s*".toRegex()

/**
 * <repository name> at <repository address>
 */
private val M2RepositoryDirectiveRegex = "(\\S+)\\s+at\\s+(\\S+:\\S+)".toRegex()

/**
 * <group> : <name> : <version>
 */
private val LibraryDirectiveRegex = "(\\S+)\\s*:\\s*(\\S+)\\s*:\\s*(\\S+)".toRegex()

/**
 * Configuration of classpath for a build script.
 */
class BuildScriptClasspathConfiguration(private val buildScriptSources: List<Path>) {
    private var _repositories: WSet<Repository>? = null
    private var _repositoryChain: RepositoryChain? = null
    private var _dependencies: WSet<Dependency>? = null

    private fun resolve() {
        val repositories = WMutableSet<Repository>()
        repositories.addAll(DefaultRepositories)
        val buildDependencyLibraries = WMutableSet<Dependency>()

        for (source in buildScriptSources) {
            var lineNumber = 0
            source.forEachLine { line ->
                lineNumber++
                val directiveMatch = DirectiveRegex.matchEntire(line) ?: return@forEachLine
                val directive = (directiveMatch.groups[1]?.value ?: "").toLowerCase()
                val value = directiveMatch.groups[2]?.value ?: ""

                when (directive) {
                    "dependency" -> {
                        val match = LibraryDirectiveRegex.matchEntire(value)
                        if (match == null) {
                            LOG.warn("{}:{} Invalid dependency directive \"{}\". (Example: 'com.example:my-project:1.0')", buildScriptSources, lineNumber, value)
                        } else {
                            val (group, name, version) = match.destructured
                            buildDependencyLibraries.add(Dependency(DependencyId(group, name, version)))
                        }
                    }
                    "repository" -> {
                        val match = M2RepositoryDirectiveRegex.matchEntire(value)
                        if (match == null) {
                            LOG.warn("{}:{} Invalid repository directive \"{}\". (Example: 'my-repo at https://example.com')", buildScriptSources, lineNumber, value)
                        } else {
                            val (name, url) = match.destructured
                            repositories.add(Repository.M2(name, URL(url), LocalM2Repository))
                        }
                    }
                    else -> {
                        LOG.warn("{}:{} Invalid directive \"{}\". Supported directives are 'dependency' and 'repository'.", buildScriptSources, lineNumber, directive)
                    }
                }
            }
        }

        _repositories = repositories
        _repositoryChain = createRepositoryChain(repositories)
        _dependencies = buildDependencyLibraries
    }

    /**
     * Repositories used when resolving dependencies for the build scripts
     */
    val repositories: WSet<Repository>
        get() {
            if (_repositories == null) {
                resolve()
            }
            return _repositories!!
        }

    /**
     * Repository chain used when resolving dependencies for the build scripts
     */
    val repositoryChain: RepositoryChain
        get() {
            if (_repositoryChain == null) {
                resolve()
            }
            return _repositoryChain!!
        }

    /**
     * Dependencies of the build script
     */
    val dependencies: WSet<Dependency>
        get() {
            if (_dependencies == null) {
                resolve()
            }
            return _dependencies!!
        }
}

/**
 * @property wemiRoot directory in which wemi executable is (./)
 * @property scriptJar jar to which the build script has been compiled
 * @property buildFolder ./build folder
 * @property cacheFolder ./build/cache folder
 * @property classpath used to compile and to run the scriptJar
 * @property initClasses main classes of the [scriptJar]
 */
data class BuildScript(val wemiRoot: Path,
                       val scriptJar: Path,
                       val buildFolder: Path, val cacheFolder: Path,
                       val classpath: WList<Path>, val initClasses: WList<String>,
                       val buildScriptClasspathConfiguration: BuildScriptClasspathConfiguration,
                       val sources: WList<LocatedFile>, val buildFlags: CompilerFlags,
                       private var ready:Boolean) {

    val wemiLauncherJar:Path
        get() = classpath.first()

    fun ready():Boolean {
        if (ready) {
            return true
        }

        LOG.debug("Compiling sources: {} classpath: {} resultJar: {} buildFlags: {}", sources, classpath, scriptJar, buildFlags)

        val status = WemiKotlinVersion.compilerInstance().compileJVM(sources, classpath, scriptJar, buildFlags, LoggerFactory.getLogger("BuildScriptCompilation"), null)
        if (status != KotlinCompiler.CompileExitStatus.OK) {
            LOG.warn("Compilation failed for {}: {}", sources, status)
            return false
        }

        ready = true
        return true
    }
}
