package wemi.boot

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.WemiKotlinVersion
import wemi.collections.WList
import wemi.collections.toWList
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
 * Build file is a file with `.kt` extension, in [buildFolder].
 */
internal fun findBuildScriptSources(buildFolder: Path): List<Path> {
    var result: ArrayList<Path>? = null

    if (buildFolder.isDirectory()) {
        Files.newDirectoryStream(buildFolder).use { stream ->
            for (path in stream) {
                if (!path.isDirectory() && !path.isHidden() && !path.name.startsWith('.')
                        && path.name.pathHasExtension("kt")) {
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
internal fun getBuildScript(cacheFolder: Path, buildScriptSources: List<Path>, forceCompile: Boolean): BuildScriptCompiler? {
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

    val resultJar = cacheFolder / "build.jar"
    val buildScriptInfoFile = cacheFolder / "build-info.json"
    val buildScriptInfo = BuildScript(resultJar, buildScriptSources.map { LocatedPath(it) }.toWList())

    var recompileReason = ""

    val recompile:Boolean = run recompile@{
        if (forceCompile) {
            recompileReason = "Requested"
            LOG.debug("Rebuilding build scripts: Requested")
            return@recompile true
        }
        if (!resultJar.exists() || resultJar.isDirectory()) {
            recompileReason = "No cache"
            LOG.debug("Rebuilding build scripts: No cache at {}", resultJar)
            return@recompile true
        }
        val jarLastModified = resultJar.lastModified
        if (buildScriptSources.any { source -> source.lastModified > jarLastModified }) {
            recompileReason = "Script changed"
            LOG.debug("Rebuilding build scripts: Old cache")
            return@recompile true
        }
        if (!buildScriptInfoFile.isRegularFile()) {
            recompileReason = "Missing cache metadata"
            LOG.debug("Rebuilding build scripts: No classpath cache")
            return@recompile true
        }
        if (Magic.WemiLauncherFile.lastModified > resultJar.lastModified) {
            recompileReason = "Updated launcher"
            LOG.debug("Rebuilding build scripts: Launcher updated ({})", Magic.WemiLauncherFile)
            return@recompile true
        }

        // Load the build-script info json
        try {
            Files.newBufferedReader(buildScriptInfoFile, Charsets.UTF_8).use {
                it.readJsonTo(buildScriptInfo)
            }
        } catch (e:Exception) {
            recompileReason = "Can't read build-script info"
            LOG.debug("Failed to build script info from {}", buildScriptInfoFile, e)
            return@recompile true
        }

        // Validate loaded data
        if (buildScriptInfo.externalClasspath.isEmpty()) {
            recompileReason = "Corrupted build-script info"
            LOG.debug("Rebuilding build scripts: Missing Wemi launcher entry in classpath")
            return@recompile true
        }

        for (classpathEntry in buildScriptInfo.externalClasspath) {
            if (!classpathEntry.exists()) {
                recompileReason = "Corrupted build-script info: classpath entries missing"
                LOG.debug("Rebuilding build scripts: Classpath cache corrupted ({} does not exist)", classpathEntry)
                return@recompile true
            }
        }

        // All is fine
        return@recompile false
    }

    // Do preparation of build-script info
    if (recompile) {
        LOG.info("Compiling build script: {}", recompileReason)
        resultJar.deleteRecursively()
        buildScriptInfo.resolve(cacheFolder)

        try {
            Files.newBufferedWriter(buildScriptInfoFile, Charsets.UTF_8).use {
                it.writeJson(buildScriptInfo, BuildScript::class.java)
            }
        } catch (e:Exception) {
            LOG.warn("Failed to save build-script info, next run will have to construct it again", e)
        }
        // Compilation is handled lazily later, in BuildScript.ready()
    }

    return BuildScriptCompiler(buildScriptInfo, recompile)
}

private fun wemiLauncherFileWithJarExtension(cacheFolder: Path): Path {
    val wemiLauncherFile = Magic.WemiLauncherFile
    if (wemiLauncherFile.name.endsWith(".jar", ignoreCase = true) || wemiLauncherFile.isDirectory()) {
        LOG.debug("wemiLauncherFileWithJarExtension used unchanged {}", wemiLauncherFile)
        return wemiLauncherFile
    }
    // We have create a link to/copy of the launcher file somewhere and name it with .jar
    val linked = cacheFolder / "wemi.jar"

    if (Files.exists(linked) && Files.exists(wemiLauncherFile.toRealPath())
            && (Files.isSameFile(linked, wemiLauncherFile) // If link
            || Files.getLastModifiedTime(wemiLauncherFile) < Files.getLastModifiedTime(linked) // If copy
                    )) {
        // Already exists and is fresh
        LOG.debug("wemiLauncherFileWithJarExtension is existing {}", linked)
        return linked
    }

    Files.deleteIfExists(linked)

    try {
        val result = Files.createSymbolicLink(linked, wemiLauncherFile)
        LOG.debug("wemiLauncherFileWithJarExtension is soft-linked {}", result)
        return result
    } catch (e: Exception) {
        LOG.debug("Failed to soft-link {} to {}", wemiLauncherFile, linked, e)
    }
    try {
        val result = Files.createLink(linked, wemiLauncherFile)
        LOG.debug("wemiLauncherFileWithJarExtension is hard-linked {}", result)
        return result
    } catch (e: Exception) {
        LOG.debug("Failed to hard-link {} to {}", wemiLauncherFile, linked, e)
    }

    try {
        Files.copy(wemiLauncherFile, linked)
        LOG.debug("WemiLauncherFileWithJar is copied {}", linked)
        return linked
    } catch (e: Exception) {
        LOG.warn("Failed to link or copy {} to {}, operations requiring Wemi launcher as jar will probably fail", wemiLauncherFile, linked, e)
        return wemiLauncherFile
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
 * Metadata about build script, needed to compile it.
 *
 * Obtained initially by resolving from source, then by loading from json.
 *
 * Also handles it's compilation, internally.
 */
class BuildScript internal constructor(
        /** jar to which the build script has been compiled */
        val scriptJar: Path,
        /** source files, from which the build script is compiled */
        val sources: WList<LocatedPath>) : JsonReadable, JsonWritable {

    private val _repositories = HashSet<Repository>()
    private val _dependencies = HashSet<Dependency>()
    // First entry of this list must be
    private val _externalClasspath = ArrayList<Path>()

    val repositories:Set<Repository>
        get() = _repositories
    val dependencies:Set<Dependency>
        get() = _dependencies
    val externalClasspath:List<Path>
        get() = _externalClasspath

    val wemiLauncherJar:Path
        get() = externalClasspath.first() // By convention

    /**
     * Main classes of the [scriptJar], that should be initialized to load the build script.
     */
    val initClasses:List<String>
        get() = sources.map { transformFileNameToKotlinClassName(it.file.name.pathWithoutExtension()) }

    /**
     * Flags used to build the build-script.
     */
    val buildFlags = CompilerFlags().also {
        it[KotlinCompilerFlags.moduleName] = "wemi-build-script"
        it[KotlinJVMCompilerFlags.jvmTarget] = "1.8"
    }

    private fun consumeDirective(annotation:Class<out Annotation>, fields:Array<String>) {
        when (annotation) {
            BuildDependency::class.java -> {
                val (groupOrFull, name, version) = fields
                if (name.isEmpty() && version.isEmpty()) {
                    _dependencies.add(Dependency(wemi.dependencyId(groupOrFull, null), WemiBundledLibrariesExclude))
                } else {
                    _dependencies.add(Dependency(DependencyId(groupOrFull, name, version), WemiBundledLibrariesExclude))
                }
            }
            BuildDependencyRepository::class.java -> {
                val (name, url) = fields

                _repositories.add(Repository.M2(name, URL(url), LocalM2Repository))
            }
            BuildClasspathDependency::class.java -> {
                val (file) = fields

                var path = Paths.get(file)
                if (!path.isAbsolute) {
                    path = WemiRootFolder.resolve(path)
                }

                try {
                    path = path.toRealPath()
                } catch (e:IOException) {
                    LOG.warn("BuildClasspathDependency - file not found and ignored {}", path)
                    return
                }

                _externalClasspath.add(path)
            }
            else -> {
                throw AssertionError(annotation)//Not possible
            }
        }
    }

    internal fun resolve(cacheFolder:Path):Boolean {
        _repositories.clear()
        _dependencies.clear()
        _externalClasspath.clear()

        // Wemi also contains Kotlin runtime
        _externalClasspath.add(wemiLauncherFileWithJarExtension(cacheFolder)) // This must be first in classpath
        _repositories.addAll(DefaultRepositories)

        for (sourceFile in sources) {
            val success = try {
                Files.newBufferedReader(sourceFile.file, Charsets.UTF_8).use {
                    parseFileDirectives(it, SupportedDirectives, ::consumeDirective)
                }
            } catch (e:Exception) {
                LOG.warn("Failed to read directives from {}", sourceFile, e)
                false
            }

            if (!success) {
                return false
            }
        }

        val resolved = HashMap<DependencyId, ResolvedDependency>()
        val resolutionOk = DependencyResolver.resolve(resolved, _dependencies, createRepositoryChain(_repositories))
        if (!resolutionOk) {
            LOG.warn("Failed to retrieve all build-script dependencies:\n{}", resolved.prettyPrint(_dependencies.map { it.dependencyId }))
            return false
        }
        _externalClasspath.addAll(resolved.artifacts())

        return true
    }

    override fun JsonWriter.write() {
        writeObject {
            fieldCollection("repositories", _repositories)
            fieldCollection("dependencies", _dependencies)
            fieldCollection("externalClasspath", _externalClasspath)
        }
    }

    override fun read(value: JsonValue) {
        value.fieldToCollection("repositories", _repositories)
        value.fieldToCollection("dependencies", _dependencies)
        value.fieldToCollection("externalClasspath", _externalClasspath)
    }

    override fun toString(): String {
        return "BuildScript(scriptJar=$scriptJar, sources=$sources, _repositories=$_repositories, _dependencies=$_dependencies, _externalClasspath=$_externalClasspath, buildFlags=$buildFlags)"
    }
}

/**
 * Handles compilation of the build script.
 *
 * @property info about the build-script, used for its compilation
 */
internal class BuildScriptCompiler (val info: BuildScript, private var compilationNeeded: Boolean) {

    internal fun ensureCompiled():Boolean {
        if (!compilationNeeded) {
            return true
        }

        LOG.debug("Compiling sources: {} classpath: {} resultJar: {} buildFlags: {}", info.sources, info.externalClasspath, info.scriptJar, info.buildFlags)

        val status = WemiKotlinVersion.compilerInstance().compileJVM(info.sources, info.externalClasspath, info.scriptJar, null, info.buildFlags, LoggerFactory.getLogger("BuildScriptCompilation"), null)
        if (status != KotlinCompiler.CompileExitStatus.OK) {
            LOG.warn("Compilation failed for {}: {}", info.sources, status)
            return false
        }

        compilationNeeded = false
        return true
    }
}
